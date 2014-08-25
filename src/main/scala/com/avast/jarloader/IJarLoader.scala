package com.avast.jarloader

import java.io.File
import java.util.Comparator

/**
 * Created <b>14.1.14</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 0.1
 */
trait IJarLoader[T] {

  def search(timeout: Int): Unit

  def search(timeout: Int, prefix: String): Unit

  def search(timeout: Int, prefix: String, suffix: String): Unit

  def init(defaultInstance: T): Unit

  def init(defaultInstance: T, defaultVersion: Int): Unit

  def init(defaultInstance: T, defaultVersion: String): Unit

  def stopSearching(): Unit

  def setComparator(comparator: Comparator[File]): Unit

  def load(name: String): Boolean

  def acceptOnlyNewer(accept: Boolean): Unit

  def acceptOnlyNewer(): Unit

  def isSearching: Boolean

  def getLoadedVersion: String

  def getLoadedClass: T
}

