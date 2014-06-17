package com.avast.jarloader.demo;

import com.avast.jarloader.IJarLoader;
import com.avast.jarloader.JarLoader;

import java.io.File;
import java.nio.file.FileSystem;
import java.util.Map;

/**
 * Created <b>15.11.13</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 * @version 0.1
 */
public class JarLoaderDemo {
    IJarLoader<IDemoFunction> loader = new JarLoader<IDemoFunction>("Demo loader", new File("/root/functions")) {
        @Override
        public void onLoad(IDemoFunction instance, String version, String className, FileSystem fs, Map<String, String> attributes) {
            System.out.println("New function loaded, class " + className + ", version " + version);
        }
    };

    public JarLoaderDemo() {
        loader.search(10000);
    }

    public static void main(String[] args) {
        new JarLoaderDemo();
    }
}
