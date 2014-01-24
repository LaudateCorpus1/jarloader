package com.avast.jarloader

import java.util.{Comparator, TimerTask, Timer}
import java.io._
import java.util
import org.slf4j.{LoggerFactory, Logger}
import java.net.{URI, JarURLConnection, URL}
import java.util.jar.Attributes
import java.nio.file._
import org.xeustechnologies.jcl.JarClassLoader
import com.avast.cloudutils.jmx.{JMXProperty, MyDynamicBean, JMXOperation}
import java.util.concurrent.atomic.AtomicReference
import scala.Some
import java.nio.file.FileSystem

/**
 * Created <b>1.11.13</b><br>
 * See <a href="https://intranet.int.avast.com/wiki/ff/projects/jar-loader/wiki">wiki</a> for docs.
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 1.1
 */
abstract class JarLoader[T](val name: Option[String], val rootDir: File, minVersion: Option[Int], maxVersion: Option[Int] = None) extends IJarLoader[T] with AbstractJarLoader[T] {
  protected val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  if (!rootDir.exists()) throw new FileNotFoundException("Cannot find requested directory '%s'".format(rootDir.getAbsolutePath))

  @JMXProperty
  protected val history = new util.ArrayList[String]

  protected var prefix: Option[String] = None
  protected var suffix: Option[String] = None
  protected var comparator: Option[Comparator[File]] = Some(new TimeFileComparator)

  protected var loadedClass: AtomicReference[Option[(T, Int)]] = new AtomicReference[Option[(T, Int)]](None)

  @JMXProperty
  protected def loadedVersion = if (loadedClass.get().isDefined) loadedClass.get.get._2 else 0

  protected var acceptOnlyNewerVersions = true

  protected val timer: Timer = new Timer("JAR loader timer")

  var timerTask: TimerTask = _

  private val jmxName: String = this.getClass.getPackage.getName + ":type=JarLoader[%s]".format(if (name.isDefined) name.get else System.currentTimeMillis())
  MyDynamicBean.exposeAndRegisterSilently(jmxName, this)

  /**
   * Constructor (prepared for Java users).
   * @param name Name of this loader.
   * @param rootDir Root directory where JAR will be searched.
   * @param minVersion Minimal version of class which will be loaded.
   * @param maxVersion Maximal version of class which will be loaded.
   */
  def this(name: String, rootDir: File, minVersion: java.lang.Integer, maxVersion: java.lang.Integer = -1.asInstanceOf[java.lang.Integer]) = {
    this(if (!"".equals(name)) Some(name) else None, rootDir, if (minVersion.compareTo(-1.asInstanceOf[java.lang.Integer]) > 1) Some(minVersion.asInstanceOf[Int]) else None, if (maxVersion.compareTo(-1.asInstanceOf[java.lang.Integer]) > 1) Some(maxVersion.asInstanceOf[Int]) else None)
  }

  /**
   * Constructor (prepared for Java users).
   * @param name Name of this loader.
   * @param rootDir Root directory where JAR will be searched.
   */
  def this(name: String, rootDir: File) = {
    this(name, rootDir, -1, -1)
  }

  def search(interval: Int, prefix: Option[String] = None, suffix: Option[String] = None): Unit = {
    this.prefix = prefix
    this.suffix = suffix

    stopSearching()

    timerTask = new TimerTask {
      def run() {
        try {
          val files: Array[File] = rootDir.listFiles(new FilenameFilter {
            def accept(dir: File, name: String): Boolean = {
              name.endsWith(".jar") && (!prefix.isDefined || name.startsWith(prefix.get)) && (!suffix.isDefined || name.substring(0, name.lastIndexOf(".")).endsWith(suffix.get))
            }
          })

          if (files == null || files.length == 0) return

          if (comparator.isDefined)
            util.Arrays.sort(files, comparator.get)

          var name: String = files(0).getName
          name = name.substring(0, name.lastIndexOf("."))
          load(name)
        }
        catch {
          case e: Exception =>
            LOGGER.error("JAR loading failed", e)
        }
      }
    }

    timer.schedule(timerTask, 0, interval)
  }

  @JMXOperation(description = "Stops searching for new implementations.")
  def stopSearching(): Unit = {
    if (timerTask != null) timerTask.cancel()
    timerTask = null
  }

  def setComparator(comparator: Comparator[File]) = {
    this.comparator = if (comparator != null) Some(comparator) else None
  }

  @JMXOperation(description = "Tries to load new JAR with given name.")
  def load(name: String): Boolean = {
    try {
      val jarFile = new File(Utils.normalizePath(rootDir.getAbsolutePath + File.separator + name + ".jar"))
      LOGGER.debug("Trying to load '" + jarFile.getAbsolutePath + "' JAR")
      if (!jarFile.exists()) {
        LOGGER.warn("Cannot find requested JAR ({})!", jarFile.getAbsolutePath)
        return false
      }

      val result = checkJar(jarFile)

      if (!result.isDefined) return false

      try {
        result.get._3.getClass.getDeclaredMethods.foreach(m => {
          if (m.isAnnotationPresent(classOf[BeforeLoad])) m.invoke(result.get._3)
        })
      }
      catch {
        case e: Throwable => throw new RuntimeException("BeforeLoad annotated method has thrown an exception", e)
      }

      try {
        if (loadedClass.get.isDefined) {
          val loaded = loadedClass.get().get._1
          loaded.getClass.getDeclaredMethods.foreach(m => {
            if (m.isAnnotationPresent(classOf[BeforeUnload])) m.invoke(loaded)
          })
        }
      }
      catch {
        case e: Throwable => LOGGER.warn("BeforeUnload annotated method has thrown an exception", e)
      }

      loadedClass.set(Some(result.get._3, result.get._1))

      LOGGER.info("Loaded class '" + result.get._3.getClass.getSimpleName + "', version " + result.get._1)
      history.add(System.currentTimeMillis() / 1000 + ";" + result.get._2 + ";" + result.get._1 + ";" + jarFile.getAbsolutePath)

      var fileSystem: FileSystem = null
      try {
        fileSystem = result.get._4
        onLoad(result.get._3, result.get._1, result.get._2, fileSystem, if (result.get._5.isDefined) result.get._5.get else new util.HashMap[String, String]())
      }
      catch {
        case e: Exception => LOGGER.warn("Exception while doing onLoad callback", e)
      } finally {
        if (fileSystem != null) fileSystem.close()
      }

      //keep the memory clean
      while (history.size() > 100) {
        history.remove(0)
      }

      true
    } catch {
      case e: Exception =>
        LOGGER.warn("JAR loading failed", e)
        false
    }
  }

  protected def checkJar(file: File): Option[(Int, String, T, FileSystem, Option[util.Map[String, String]])] = {
    val classPackage = if (loadedClass.get.isDefined) {
      val n = loadedClass.get.get._1.getClass.getName
      n.substring(0, n.lastIndexOf("."))
    }
    else ""

    val url = new URL("jar:file:" + file.getAbsolutePath + "!/")
    val jcl = new JarClassLoader()
    jcl.add(file.getName)

    val uc = url.openConnection().asInstanceOf[JarURLConnection]
    val attr = uc.getMainAttributes

    val className = attr.get(Attributes.Name.MAIN_CLASS).asInstanceOf[String]
    val version = java.lang.Integer.parseInt(attr.get(Attributes.Name.IMPLEMENTATION_VERSION).asInstanceOf[String])

    if (version <= loadedVersion && acceptOnlyNewerVersions) {
      LOGGER.debug("Too old version: {} (loaded: {}, required newer)", version, loadedVersion)
      return None
    }
    if (minVersion.isDefined && minVersion.get > version) {
      LOGGER.debug("Too small version: {} (required: {})", version, minVersion.get)
      return None
    }
    if (maxVersion.isDefined && maxVersion.get < version) {
      LOGGER.debug("Too big version: {} (required: {})", version, maxVersion.get)
      return None
    }

    if (className == null || "" == className) throw new Exception("Main class attr empty")
    if (!className.startsWith(classPackage)) return None

    //manifest ok, load properties

    val uri: URI = URI.create("jar:file:" + file.toURI.getPath.replaceAll(" ", "%2520"))
    try {
      FileSystems.getFileSystem(uri).close()
    }
    catch {
      case _: FileSystemNotFoundException => //ok
    }
    val jarFs = FileSystems.newFileSystem(uri, new util.HashMap[String, String]())

    val name: String = file.getName.toLowerCase
    val properties = loadProperties(jarFs.getPath("/" + name.substring(0, name.lastIndexOf(".")) + ".properties"))

    val plugin = jcl.loadClass(className).newInstance().asInstanceOf[T] //create instance

    Some(version, className, plugin, jarFs, properties)
  }

  protected def loadProperties(path: Path): Option[util.Map[String, String]] = {
    try {
      val properties = new PropertiesLoader(Files.newInputStream(path)).getEntries
      if (properties != null && properties.size() > 0) Some(properties) else None
    }
    catch {
      case e: IOException =>
        LOGGER.debug("Properties file not found: " + path.getFileName)
        None
      case e: Exception =>
        LOGGER.warn("Error while loading properties file: " + path.getFileName, e)
        None
    }
  }

  @JMXProperty(name = "searching")
  def isSearching: Boolean = {
    timerTask != null
  }

  @JMXProperty(name = "loadedFunction")
  def getLoadedFunctionClassName = {
    if (loadedClass.get.isDefined) loadedClass.get.getClass.getName else null
  }

  def init(defaultClass: T, defaultVersion: Option[Int]) {
    loadedClass.set(Some(defaultClass, if (defaultVersion.isDefined) defaultVersion.get else 0))
  }

  def acceptOnlyNewer(accept: Boolean) = {
    acceptOnlyNewerVersions = accept
  }

  def acceptOnlyNewer() {
    acceptOnlyNewer(accept = false)
  }

  def getLoadedVersion: Int = loadedVersion

  def getLoadedClass: T = if (loadedClass.get.isDefined) loadedClass.get.get._1 else null.asInstanceOf[T]

  @JMXOperation(description = "Saves history of loaded JARs to specified file.")
  def saveHistoryToCsv(name: String, colSeparator: String) = {
    val wr = new BufferedWriter(new FileWriter(name))

    val it = history.iterator()
    while (it.hasNext) {
      val s = it.next()
      wr.append(s.replaceAll(";", colSeparator))
      wr.newLine()
    }

    wr.flush()
    wr.close()

    LOGGER.info("JAR loader history written to '{}'", name)
  }

  /**
   * Callback invoked after some JAR has been loaded.
   * @param instance Loaded instance.
   * @param version Version of loaded JAR.
   * @param className Name (fully classified) of loaded class.
   * @param fs Filesystem of the JAR.
   * @param attributes Attributes loaded from properties file.
   */
  protected def onLoad(instance: T, version: Int, className: String, fs: FileSystem, attributes: util.Map[String, String])

  /* -- java interface methods -- */

  @JMXOperation(description = "Starts search with given interval, suffix and prefix.")
  def search(timeout: Int, prefix: String, suffix: String) = {
    search(timeout, Some(prefix), Some(suffix))
  }

  def search(timeout: Int, prefix: String) = {
    search(timeout, Some(prefix), None)
  }

  def search(timeout: Int) = {
    search(timeout, None, None)
  }

  def init(defaultInstance: T, defaultVersion: Int) = {
    init(defaultInstance, Some(defaultVersion))
  }

  def init(defaultInstance: T) = {
    init(defaultInstance, None)
  }
}