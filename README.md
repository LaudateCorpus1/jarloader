JAR loader
=============

This library servers for dynamic loading of JARs with miscellaneous content. Typical usage is in project with time-various decision making function. In order to not recompile and restart the service each time this function is changed, the new function implementation can be loaded from separate JAR via this library.

The library is implemented in Scala, but is prepared for usage both in Scala and Java - the main class `JarLoader` implements `AbstractJarLoader` Scala trait and `IJarLoader` Java interface at the same time and it's up to you which you will use. Methods in both interfaces are equal, but the Scala trait uses some `Option` parameters, instead of Java overloading.
This is very basic usage:

		public class JarLoaderDemo {
			IJarLoader<IDemoFunction> loader = new JarLoader<IDemoFunction>("Demo loader", new File("/root/functions")) {
				@Override
				public void onLoad(IDemoFunction instance, int version, String className, FileSystem fs, Map<String, String> attributes) {
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

This code means the loader will look for new function implementations in `/root/functions` dir every 10 seconds. By default, it accepts only newer implementations then is the current one - this is recognized by version, provided in JARs manifest. In the manifest has to be also the main class of the JAR (the one, which implements the `IDemoFunction` interface). Minimum manifest content is this:

    Main-Class: com.avast.project.DefaultFunction
    Implementation-Version: 3

The `onLoad` methods offers you following parameters:
  * instance of loaded class
  * version of implementation (from the manifest)
  * fully classified name of loaded class (as stated in the manifest)
  * FileSystem, which you can use for some following initialization - access into the loaded JAR
  * attributes, loaded from properties file (see below)

# Properties file
Optional content of the JAR is properties file (format: `{fileNameOfJarWithoutExtension}.properties`), containing some parameters for initialization. This properties file is loaded and passed to the `onLoad` method as `attributes` parameter.
# Loading options
The demo above shows the very basic usage. On the other hand, you can specify prefix and/or suffix of the JARs name by passing these parameters to the `search` method. Additionally, the constructor can accept also minimal and maximal version, which can be loaded (this is useful for compatibility issues). You can also specify (by `acceptOnlyNewer` method) that you want to load also older versions then is the current one.

It is expected in the given root dir will be multiple JARs with functions implementation. While loading a new one, all files (satisfying given prefix and suffix, if set) are sorted by comparator and the first one is loaded. The default comparator is `TimeFileComparator` (descending, so the winner is the newest file - mTime is used), but you can use also `AplhaFileComparator` (descending too, alphabetically sorted last file wins) or any other comparator, implementing `Comparator<File>` interface.

# Runtime configuration
The loader is exposed to JMX view, if possible. You can see currently loaded class and its version, history of loaded implementations (last 100). Available operations are `load`, `saveHistoryToCsv`,`search` and `stopSearching`.
The loaders JMX name is `JarLoader[{nameGivenInConstructor}]`, or `JarLoader[{currentMillis}]` if no name is provided. All loaders are placed (in JMX) in the same package as the class which owns (or extends) it.
