package com.avast.jarloader

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.Properties
import java.util.regex.Matcher

import scala.collection.JavaConversions._

object Utils {
  def normalizePath(path: String): String = {
    val p = Paths.get(path.trim().replaceAll("\\.\\.\\.", "\\.\\."))
      .normalize()
      .toString
      .replaceAll(Matcher.quoteReplacement(File.separator + File.separator), Matcher.quoteReplacement(File.separator))

    if (!p.endsWith(File.separator)) p else p.substring(0, p.length - 1)
  }

  def fileAsString(input: InputStream): String = {
    try {
      val fileData = Array.ofDim[Byte](input.available())
      input.read(fileData)
      new String(fileData, "UTF-8")
    } catch {
      case e: IOException => null
    } finally {
      try {
        if (input != null) input.close()
      } catch {
        case e: IOException =>
      }
    }
  }

  def fileAsString(path: Path): String = {
    try {
      fileAsString(Files.newInputStream(path))
    } catch {
      case e: IOException => null
    }
  }

  def fileAsString(file: File): String = {
    try {
      fileAsString(new FileInputStream(file))
    } catch {
      case e: FileNotFoundException => null
    }
  }

  protected val versionComparator = new VersionComparator

  /**
   * Compares two versions.
   * @param oldVersion The old version.
   * @param newVersion The new version.
   * @return TRUE if newVersion is really newer.
   */
  def isNewerVersion(oldVersion: String, newVersion: String): Boolean = {
    versionComparator.compare(oldVersion, newVersion) == -1
  }
}

class PropertiesLoader(is: InputStream) {

  var properties: Properties = new Properties()

  properties.load(is)

  def this(file: File) {
    this(new FileInputStream(file))
  }

  def this(file: String) {
    this(new FileInputStream(file))
  }

  def get(key: String): String = properties.getProperty(key)

  def getEntries: util.Map[String, String] = {
    val m = new util.HashMap[String, String](properties.size)
    for ((key, value) <- properties) m.put(key.asInstanceOf[String], value.asInstanceOf[String])
    m
  }
}
