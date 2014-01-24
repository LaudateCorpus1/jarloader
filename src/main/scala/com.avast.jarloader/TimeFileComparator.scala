package com.avast.jarloader

import java.io.File
import java.util.Comparator

/**
 * Created <b>4.11.13</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 0.1
 */
class TimeFileComparator extends Comparator[File] {
  def compare(o1: File, o2: File): Int = {
    if (o1.lastModified < o2.lastModified) 1 else if (o1.lastModified > o2.lastModified) -1 else 0 //descendant order
  }
}
