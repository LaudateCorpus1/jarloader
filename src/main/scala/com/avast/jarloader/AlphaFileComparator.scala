package com.avast.jarloader

import java.io.File
import java.util.Comparator

/**
 * Created <b>4.11.13</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 0.1
 */
class AlphaFileComparator extends Comparator[File] {
  protected val comparator = new VersionComparator

  def compare(o1: File, o2: File): Int = {
    val name1 = o1.getName.substring(0, o1.getName.length - 4)
    val name2 = o2.getName.substring(0, o2.getName.length - 4)

    comparator.compare(name1, name2)
  }
}
