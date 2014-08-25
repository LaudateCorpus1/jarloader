package com.avast.jarloader

import java.util.Comparator

/**
 * Created <b>4.11.13</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 0.1
 */
class AlphaComparator extends Comparator[String] {
  def compare(name1: String, name2: String): Int = {
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
