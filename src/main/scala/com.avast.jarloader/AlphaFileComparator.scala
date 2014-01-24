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
    o1.getName.compareTo(o2.getName) * -1 //descendant order
  }
}
