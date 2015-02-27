package com.avast.jarloader

import org.scalatest.FunSuite

/**
 * Created <b>27.2.2015</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 */
class VersionComparatorTest extends FunSuite {
  test("compare") {
    val c = new VersionComparator

    val v = List(
      ("0", "0", false),
      ("0", "1", true),
      ("0.1", "1.0", true),
      ("0.1", "0.1", false),
      ("0.1", "0.11", true),
      ("0.1", "0.1.11", true),
      ("0.1", "0.1.11.1.1.1", true),
      ("0.2", "0.1.11.1.1.1", false),
      ("0.2.1.1.1.1", "0.1.11.1.1.1", false),
      ("0.1", "0.1.1", true),
      ("0.1", "0.2", true),
      ("0.1.1", "0.2", true),
      ("0.2.1", "0.2", false),
      ("0.1.1", "0.1.79", true),
      ("0.1.1", "0.1.79-2", true),
      ("0.1.79-1", "0.1.79-2", true),
      ("0.1.79-r1", "0.1.79-r2", true),
      ("0.1.79", "0.1.79-r2", true),
      ("0.1.80", "0.1.79-r2", false),
      ("0.1.79-r2", "0.1.79-r2_a", true),
      ("0.1.79-r2_a", "0.1.79-r2_b", true),
      ("0.21", "0.1.79-r2_b", false),
      ("0.21", "2.1-version_r2", true),
      ("libname", "libname-1.0", true),
      ("libname", "libname-1.0.1", true),
      ("libname", "libname-1.1.0", true),
      ("libname-1.0", "libname-1.1.0", true),
      ("libname-1.0", "libname-1.0.1", true),
      ("libname-1.0", "libname-1.0", false),
      ("libname-0.1", "libname-1.0", true),
      ("libname-1.0", "libname-r2-1.0", false), //this is quite weird case...
      ("libname-1.0", "libname-1.0-r2", true),
      ("0.1", "1", true)
    )

    v.foreach(i => assert((c.compare(i._1, i._2) == -1) == i._3, "%s %s newer than %s".format(i._2, if (i._3) "SHOULD BE" else "SHOULD NOT BE", i._1)))
  }
}
