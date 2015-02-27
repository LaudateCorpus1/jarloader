package com.avast.jarloader

import java.util.Comparator

/**
 * Created <b>4.11.13</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 */
class VersionComparator extends Comparator[String] {

  def compare(version1: String, version2: String): Int = {
    if (version1 == null && version2 != null) return -1
    else if (version1 != null && version2 == null) return 1
    else if (version1 == null && version2 == null) return 0

    //are versions plain numbers?
    if (version1.matches("\\d+(\\.\\d+)?") && version2.matches("\\d+(\\.\\d+)?")) return version1.toDouble.compareTo(version2.toDouble)

    //split to parts, supported are . - _
    val parts1 = version1.split(VersionComparator.splitChars)
    val parts2 = version2.split(VersionComparator.splitChars)

    val l1 = parts1.length
    val l2 = parts2.length

    val l = math.min(l1, l2)

    //compare part after part
    for (i <- 0 to l - 1) {
      val p1 = parts1(i)
      val p2 = parts2(i)

      //if it's possible, compare as numbers (now only INT)
      val c = if (p1.matches("\\d+") && p2.matches("\\d+")) {
        p1.toInt.compareTo(p2.toInt)
      } else {
        p1.compareTo(p2)
      }

      if (c != 0) return c
    }

    //versions are same, but one is longer ;-) e.g. 1.1 and 1.1.1
    l1.compareTo(l2)
  }
}

object VersionComparator {
  private val splitChars: Array[Char] = Array('.', '-', '_')
}
