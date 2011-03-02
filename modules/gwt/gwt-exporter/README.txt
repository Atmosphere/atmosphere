
This is the GWT Exporter module courtesy of Timepedia.org.  This module
allows you to expose GWT classes, methods, and fields as Javascript
objects and takes care of marshalling GWT objects back and forth
between the Javascript interface and the GWT interface.

Feel free to use, modify, and redistribute this code according to the
provisions of the Apache License 2.0.

If you want to contribute, feel free to submit issues to the issue
tracker on http://code.google.com/gwt-exporter or submit patches.
You can email me directly at timepedia@gmail.com, or you can
try the GWT Groups on googlegroups.com

Right now, there's no build files, because we use a cross platform
Maven configuration that depends on an internal repository that hosts
all of the GWT library permutations. I welcome anyone who wants to
provide working ANT/Maven build files that will work 'out of the box'
or with minimum end user hassle.

Also, unit tests are another area that needs work.

There is no documentation right now, but see the samples directory
for example usage. The exporter source itself is heavily commented.

You can also read the Timepedia blog, http://timepedia.blogspot.com
for a discussion of how everything works.

-Ray Cromwell

