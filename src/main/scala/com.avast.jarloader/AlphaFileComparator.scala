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
  def compare(o1: File, o2: File): Int = {
    val name1 = o1.getName.substring(0, o1.getName.length - 4)
    val name2 = o2.getName.substring(0, o2.getName.length - 4)

    val l1 = name1.length
    val l2 = name2.length

    val l = math.min(l1, l2)

    val name1Short = name1.substring(0, l)
    val name2Short = name2.substring(0, l)

    if (l1 == l2) name1Short.compareTo(name2Short) * -1
    else {
      if (l1 > l2) -1 else 1
    }
  }
}
