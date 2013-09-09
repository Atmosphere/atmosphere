#!/bin/bash -v
# Update JXR and JavaDoc in gh-pages.
# Author: Hubert Iwaniuk
# Licence: ../LICENSE-2.0.txt

git checkout master && \
git pull && \
mvn clean jxr:aggregate javadoc:aggregate && \
git checkout gh-pages && \
for x in apidocs xref; do rm -r $x && git rm -r $x && mv target/site/$x ./ && git add $x; done && \
cp stylesheets/javadoc-stylesheet.css apidocs/stylesheet.css && \
git commit -m "JXR & JavaDoc update" && \
git push origin gh-pages && \
git checkout master
