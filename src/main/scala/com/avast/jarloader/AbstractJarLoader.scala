package com.avast.jarloader

import java.io.File
import java.util.Comparator

/**
 * Created <b>11.11.13</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 1.1
 */
trait AbstractJarLoader[T] {
  /**
   * Starts searching for new JARs.
   *
   * @param interval Interval of searching in millis.
   * @param prefix  Required prefix of the JAR filename.
   * @param suffix  Required suffix of the JAR filename.
   */
  def search(interval: Int, prefix: Option[String] = None, suffix: Option[String] = None): Unit

  /**
   * Sets default loaded class (and possibly also the version).
   * @param defaultClass The default class.
   * @param defaultVersion The default version.
   */
  def init(defaultClass: T, defaultVersion: Option[String])

  /**
   * Stops searching for new JARs.
   */
  def stopSearching()

  /**
   * Sets comparator for sorting of found JARs. By default <code>TimeFileComparator</code>.
   *
   * @param comparator The comparator.
   * @see TimeFileComparator
   * @see AlphaFileComparator
   */
  def setComparator(comparator: Comparator[File])

  /**
   * Loads new function from JAR described by the name parameter.
   *
   * @param name Name of the JAR file, <b>WITHOUT</b> the <code>.jar</code> extension. Typically only the name of the file.
   * @return TRUE if the load was successful.
   */
  def load(name: String): Boolean

  /**
   * Determines whether also older version then the currently loaded should be loaded.
   *
   * @param accept Accept only newer versions then current?
   */
  def acceptOnlyNewer(accept: Boolean)

  /**
   * Load only newer version then the currently loaded one.
   */
  def acceptOnlyNewer()

  /**
   * Determines whether this loader is currently searching for new JARs.
   * @return TRUE if the loader is searching.
   */
  def isSearching: Boolean

  /**
   * Gets currently loaded version of JAR.
   *
   * @return The version.
   */
  def getLoadedVersion: String

  /**
   * Gets currently loaded class (instance).
   *
   * @return The instance.
   */
  def getLoadedClass: T
}
