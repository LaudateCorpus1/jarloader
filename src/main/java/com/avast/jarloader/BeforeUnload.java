package com.avast.jarloader;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created <b>23.1.14</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 0.1
 */


@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BeforeUnload {
}