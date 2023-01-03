java-common-libs
================

This repository contains a set of libraries used by OpenCB projects that use the Java language. It is composed of three parts:

* bioformats: Readers and writers of file formats typically used in bioformatics
* commons: Data structures and utility classes
* math: Mathematical functions


## Gitlab pipeline

We push artifacts to the package registry on the Gitlab project. Cellbase will pull from here.

We can follow [Gitlab instructions for Maven](https://docs.gitlab.com/ee/user/packages/maven_repository/index.html#create-maven-packages-with-gitlab-cicd) on how to do this. To assist, this is automated in the `.gitlab-ci.yml` file:
* ci_settings.xml - Same as the Gitlab instructions.
* ci_repositories.xml - This gets appended to the `pom.xml`.
* A `sed` command in the pipeline to edit the `pom.xml` before appending `ci_repositories.xml`.
* There may be a more Java way to make these changes; this is just a quick edit using Linux tools.
