package com.avast.jarloader

import java.io._
import java.net.{JarURLConnection, URI, URL}
import java.nio.file.{FileSystem, _}
import java.util
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, ScheduledFuture, Semaphore, TimeUnit}
import java.util.jar.Attributes

import com.avast.jmx.{JMXOperation, JMXProperty, MyDynamicBean}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.slf4j.{Logger, LoggerFactory}
import org.xeustechnologies.jcl.JclObjectFactory

/**
 * Created <b>1.11.13</b><br>
 * See <a href="https://intranet.int.avast.com/wiki/ff/projects/jar-loader/wiki">wiki</a> for docs.
 *
 * @author Jenda Kolena, kolena@avast.com
 */
abstract class JarLoader[T](val name: Option[String], val rootDir: File, minVersion: Option[String], maxVersion: Option[String] = None) extends IJarLoader[T] with AbstractJarLoader[T] {
  protected val LOGGER: Logger = LoggerFactory.getLogger(getClass)

  if (!rootDir.exists()) throw new FileNotFoundException("Cannot find requested directory '%s'".format(rootDir.getAbsolutePath))

  @JMXProperty
  protected val history = new util.ArrayList[String]

  protected var prefix: Option[String] = None
  protected var suffix: Option[String] = None
  protected var comparator: Option[Comparator[File]] = Some(new AlphaFileComparator)

  protected val loadingLock = new Semaphore(1)

  protected var loadedClass: AtomicReference[Option[(T, String)]] = new AtomicReference[Option[(T, String)]](None)

  @JMXProperty
  protected def loadedVersion = if (loadedClass.get().isDefined) loadedClass.get.get._2 else 0.toString

  @JMXProperty(setable = true)
  protected var acceptOnlyNewerVersions = true

  @JMXProperty(setable = true)
  protected var allowAutoSwitch = true

  protected val loader = new AtomicReference[InJarClassLoader]()

  protected var waitingLoader: InJarClassLoader = null
  protected var waitingJarResult: (String, String, T, java.nio.file.FileSystem, Option[util.Map[String, String]]) = null

  @JMXProperty()
  protected var waitingJarFile: String = null

  protected var timerTask: ScheduledFuture[_] = _

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

    LOGGER.info(s"Starts searching with interval $interval, prefix ${prefix.getOrElse("-")}, suffix ${suffix.getOrElse("-")}")

    timerTask = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("jarloadertimer-" + (if (name.isDefined) name.get else System.currentTimeMillis()) + "-%d").build()).scheduleAtFixedRate(new Runnable {
      override def run(): Unit = {
        try {
          val files: Array[File] = rootDir.listFiles(new FilenameFilter {
            def accept(dir: File, name: String): Boolean = {
              name.endsWith(".jar") && (!prefix.isDefined || name.startsWith(prefix.get)) && (!suffix.isDefined || name.substring(0, name.lastIndexOf(".")).endsWith(suffix.get))
            }
          })

          if (files == null || files.length == 0) return

          if (!allowAutoSwitch && waitingJarFile != null && waitingJarResult != null && waitingLoader != null) {
            LOGGER.debug(s"JAR file $waitingJarFile with version ${waitingJarResult._1} is already waiting for switch")
            return
          }

          if (comparator.isDefined)
            util.Arrays.sort(files, comparator.get)

          var name: String = files(0).getName
          name = name.substring(0, name.lastIndexOf("."))

          LOGGER.debug("Found %s suitable files, %s chosen".format(files.length, name))

          load(name)
        }
        catch {
          case e: Exception =>
            LOGGER.error("JAR loading failed", e)
        }
      }
    }, 0, interval, TimeUnit.MILLISECONDS)

    //thread checker
    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(new Runnable {
      override def run() = {
        if (timerTask != null && timerTask.isDone) {
          //should be running but is not
          LOGGER.info("Loading thread probably died, restarting")
          search(interval, prefix, suffix) //restart
        }
      }
    }, 1, 1, TimeUnit.MINUTES)
  }

  @JMXOperation(description = "Stops searching for new implementations.")
  def stopSearching(): Unit = {
    if (timerTask != null) timerTask.cancel(false)
    timerTask = null
    LOGGER.info("Stops searching")
  }

  def setComparator(comparator: Comparator[File]) = {
    this.comparator = if (comparator != null) Some(comparator) else None
  }

  @JMXOperation(description = "Tries to load new JAR with given name (without JAR extension). Can take a long time to execute.")
  def load(name: String): Boolean = {
    if (!loadingLock.tryAcquire()) return false

    try {
      val path = if (!Paths.get(name).isAbsolute) {
        rootDir.getAbsolutePath + File.separator + name
      }
      else name

      val jarFile = new File(Utils.normalizePath(if (!path.endsWith(".jar")) path + ".jar" else path))
      LOGGER.debug("Trying to load '" + jarFile.getAbsolutePath + "' JAR")
      if (!jarFile.exists()) {
        LOGGER.warn("Cannot find requested JAR ({})!", jarFile.getAbsolutePath)
        return false
      }

      val result = checkJar(jarFile)

      if (!result.isDefined) return false

      try {
        val inst: T = result.get._3

        inst.getClass.getDeclaredMethods.filter(m => {
          m.isAnnotationPresent(classOf[BeforeLoad])
        }).foreach(m => {
          m.invoke(inst)
        })
      }
      catch {
        case e: Throwable => throw new RuntimeException("BeforeLoad annotated method has thrown an exception", e)
      }

      waitingJarResult = result.get
      waitingJarFile = jarFile.getAbsolutePath

      if (allowAutoSwitch) {
        performSwitch()
        true
      }
      else {
        LOGGER.info("New JAR with version {} loaded, waiting for confirmation", result.get._1)
        true
      }
    } catch {
      case e: Exception =>
        LOGGER.warn("JAR loading failed", e)
        false
      case e: Throwable =>
        LOGGER.error("JAR loading failed", e)
        throw e
    } finally {
      loadingLock.release()
    }
  }

  protected def performSwitch() = {
    try {
      if (loadedClass.get.isDefined) {
        val inst: T = loadedClass.get().get._1

        val executor = Executors.newFixedThreadPool(2)

        inst.getClass.getDeclaredMethods.filter(m => {
          m.isAnnotationPresent(classOf[BeforeUnload])
        }).foreach(m => {
          executor.submit(new Runnable {
            override def run() = {
              try {
                m.invoke(inst)
              }
              catch {
                case e: Throwable => LOGGER.warn(s"BeforeUnload annotated method ${m.getName} has thrown an exception", e)
              }
            }
          })
        })
      }
    }
    catch {
      case e: Throwable => LOGGER.warn("Execution of BeforeUnload method has failed", e)
    }

    var fileSystem: FileSystem = null
    try {
      fileSystem = waitingJarResult._4
      onLoad(waitingJarResult._3, waitingJarResult._1, waitingJarResult._2, fileSystem, if (waitingJarResult._5.isDefined) waitingJarResult._5.get else new util.HashMap[String, String]())
    }
    catch {
      case e: Throwable => LOGGER.warn("Exception while doing onLoad callback", e)
    } finally {
      if (fileSystem != null) fileSystem.close()
    }

    LOGGER.debug("Switching to new version")

    loadedClass.set(Some(waitingJarResult._3, waitingJarResult._1))
    val oldLoader = loader.getAndSet(waitingLoader)

    LOGGER.info("Loaded class '" + waitingJarResult._3.getClass.getSimpleName + "', version " + waitingJarResult._1)
    history.add(System.currentTimeMillis() / 1000 + ";" + waitingJarResult._2 + ";" + waitingJarResult._1 + ";" + waitingJarFile)
    //keep the memory clean
    while (history.size() > 100) {
      history.remove(0)
    }

    if (oldLoader != null)
      oldLoader.release()

    //these references are no longer needed
    waitingLoader = null
    waitingJarFile = null
    waitingJarResult = null
  }

  protected def checkJar(file: File): Option[(String, String, T, java.nio.file.FileSystem, Option[util.Map[String, String]])] = {
    val classPackage = if (loadedClass.get.isDefined) {
      val n = loadedClass.get.get._1.getClass.getName
      n.substring(0, n.lastIndexOf("."))
    }
    else ""

    val uc = new URL("jar:file:" + file.getAbsolutePath + "!/").openConnection().asInstanceOf[JarURLConnection]
    val attr = uc.getMainAttributes

    val className = attr.getValue(Attributes.Name.MAIN_CLASS)
    val version = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION)

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

    waitingLoader = new InJarClassLoader(uc)

    Some(version, className, JclObjectFactory.getInstance().create(waitingLoader.getLoader, className).asInstanceOf[T], jarFs, properties)
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
    acceptOnlyNewer(accept = true)
  }


  def enableAutoSwitching(enable: Boolean) = {
    allowAutoSwitch = enable
  }

  @JMXOperation
  def confirmSwitch(): String = {
    if (allowAutoSwitch) throw new IllegalStateException("AutoSwitch is enabled")

    if (waitingLoader == null || waitingJarFile == null || waitingJarResult == null) return ""

    try {
      performSwitch()
      loadedVersion
    }
    catch {
      case e: Exception => throw new RuntimeException(s"JAR $waitingJarFile could not be loaded because of error", e)
    }
  }

  def isAutoSwitchingEnabled: Boolean = allowAutoSwitch

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

  @JMXOperation(description = "Starts search with given interval [ms], suffix and prefix.")
  def search(timeout: Int, prefix: String, suffix: String) = {
    search(timeout, Some(prefix), Some(suffix))
  }

  @JMXOperation(description = "Starts search with given interval [ms] and suffix.")
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