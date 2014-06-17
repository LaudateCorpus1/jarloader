package com.avast.jarloader

import java.io._
import java.net.{JarURLConnection, URI, URL}
import java.nio.file._
import java.util
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Attributes
import java.util.{Comparator, Timer, TimerTask}

import com.avast.jmx.{JMXOperation, JMXProperty, MyDynamicBean}
import org.slf4j.{Logger, LoggerFactory}
import org.xeustechnologies.jcl.{JarClassLoader, JclObjectFactory}

/**
 * Created <b>1.11.13</b><br>
 * See <a href="https://intranet.int.avast.com/wiki/ff/projects/jar-loader/wiki">wiki</a> for docs.
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 1.1
 */
abstract class JarLoader[T](val name: Option[String], val rootDir: File, minVersion: Option[String], maxVersion: Option[String] = None) extends IJarLoader[T] with AbstractJarLoader[T] {
  protected val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  if (!rootDir.exists()) throw new FileNotFoundException("Cannot find requested directory '%s'".format(rootDir.getAbsolutePath))

  @JMXProperty
  protected val history = new util.ArrayList[String]

  protected var prefix: Option[String] = None
  protected var suffix: Option[String] = None
  protected var comparator: Option[Comparator[File]] = Some(new AlphaFileComparator)

  protected var loadedClass: AtomicReference[Option[(T, String)]] = new AtomicReference[Option[(T, String)]](None)

  @JMXProperty
  protected def loadedVersion = if (loadedClass.get().isDefined) loadedClass.get.get._2 else 0.toString

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
    this(if (!"".equals(name)) Some(name) else None, rootDir, if (minVersion.compareTo(-1.asInstanceOf[java.lang.Integer]) > 1) Some(minVersion.toString) else None, if (maxVersion.compareTo(-1.asInstanceOf[java.lang.Integer]) > 1) Some(maxVersion.toString) else None)
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

      var fileSystem: java.nio.file.FileSystem = null
      try {
        fileSystem = result.get._4
        onLoad(result.get._3, result.get._1, result.get._2, fileSystem, if (result.get._5.isDefined) result.get._5.get else new util.HashMap[String, String]())
      }
      catch {
        case e: Exception => LOGGER.warn("Exception while doing onLoad callback", e)
      } finally {
        if (fileSystem != null) fileSystem.close()
      }

      loadedClass.set(Some(result.get._3, result.get._1))

      LOGGER.info("Loaded class '" + result.get._3.getClass.getSimpleName + "', version " + result.get._1)
      history.add(System.currentTimeMillis() / 1000 + ";" + result.get._2 + ";" + result.get._1 + ";" + jarFile.getAbsolutePath)
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

  protected def checkJar(file: File): Option[(String, String, T, java.nio.file.FileSystem, Option[util.Map[String, String]])] = {
    val classPackage = if (loadedClass.get.isDefined) {
      val n = loadedClass.get.get._1.getClass.getName
      n.substring(0, n.lastIndexOf("."))
    }
    else ""

    val uc = new URL("jar:file:" + file.getAbsolutePath + "!/").openConnection().asInstanceOf[JarURLConnection]
    val attr = uc.getMainAttributes

    val className = attr.get(Attributes.Name.MAIN_CLASS).asInstanceOf[String]
    val version = attr.get(Attributes.Name.IMPLEMENTATION_VERSION).asInstanceOf[String]

    if (Utils.compareVersions(loadedVersion, version) >= 0 && acceptOnlyNewerVersions) {
      LOGGER.debug("Too old version: %s (loaded: %s, required newer)".format(version, loadedVersion))
      return None
    }
    if (minVersion.isDefined && Utils.compareVersions(minVersion.get, version) < 0) {
      LOGGER.debug("Too small version: %s (required: %s)".format(version, minVersion.get))
      return None
    }
    if (maxVersion.isDefined && Utils.compareVersions(maxVersion.get, version) < 0) {
      LOGGER.debug("Too big version: %s (required: %s)".format(version, maxVersion.get))
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

    val jcl = new JarClassLoader()
    jcl.add(uc.getJarFileURL)

    val plugin = JclObjectFactory.getInstance().create(jcl, className).asInstanceOf[T] //create instance

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

  @JMXProperty(name = "loadedClassName")
  def getLoadedClassName = {
    if (loadedClass.get.isDefined) loadedClass.get.get._1.getClass.getName else null
  }

  def init(defaultClass: T, defaultVersion: Option[String]) {
    loadedClass.set(Some(defaultClass, if (defaultVersion.isDefined) defaultVersion.get else 0.toString))
  }

  def acceptOnlyNewer(accept: Boolean) = {
    acceptOnlyNewerVersions = accept
  }

  def acceptOnlyNewer() {
    acceptOnlyNewer(accept = false)
  }

  def getLoadedVersion: String = loadedVersion

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
   * Callback invoked after some JAR has been loaded, but before switch of classes reference.
   * @param instance Loaded instance.
   * @param version Version of loaded JAR.
   * @param className Name (fully classified) of loaded class.
   * @param fs Filesystem of the JAR.
   * @param attributes Attributes loaded from properties file.
   */
  protected def onLoad(instance: T, version: String, className: String, fs: java.nio.file.FileSystem, attributes: util.Map[String, String])

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
    init(defaultInstance, Some(defaultVersion.toString))
  }

  def init(defaultInstance: T, defaultVersion: String) = {
    init(defaultInstance, Some(defaultVersion))
  }

  def init(defaultInstance: T) = {
    init(defaultInstance, None)
  }
}