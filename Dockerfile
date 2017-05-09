#
# Scala base DockerfileOracle Java 8 Dockerfile
# https://github.com/rorygraves/scalabase/
#

# Pull base image from phusion
FROM  phusion/baseimage                                                                                                 

MAINTAINER Rory Graves, rory.graves@fieldmark.co.uk

# Install git and a few other useful gits
RUN\
  echo "*** installing curl git locals ca-certifcates" &&\
  apt-get update  &&\
  echo "*** installing git, curl, locals and certificates" &&\
  apt-get install -y curl git locales ca-certificates  &&\
  echo "*** generating locals" &&\
  echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen &&\
  locale-gen &&\
  echo "*** installing java 8" &&\
  echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
  add-apt-repository -y ppa:webupd8team/java && \
  apt-get update && \
  apt-get install -y oracle-java8-installer && \
  rm -rf /var/lib/apt/lists/* && \
  rm -rf /var/cache/oracle-jdk8-installer &&\
  apt-get clean &&\
  echo "*** linking java to orache java 8" &&\
  rm /usr/bin/java &&\
  ln -s /usr/lib/jvm/java-8-oracle/bin/java /usr/bin/java

# Define commonly used JAVA_HOME variable
ENV JAVA_HOME /usr/lib/jvm/java-8-oracle

RUN \
  java -version &&\
  javac -version

ENV SBT_VERSION 0.13.12

RUN wget --no-check-certificate https://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb

RUN dpkg -i sbt-$SBT_VERSION.deb

################################################
# SBT (and by implication, Scala)
#ADD https://raw.githubusercontent.com/paulp/sbt-extras/master/sbt /usr/bin/sbt
#RUN chmod a+rx /usr/bin/sbt

RUN \
  echo "*** creating user" &&\
  groupadd -r robot -g 433 &&\
  useradd -u 431 -r -g robot -d /home/robot robot &&\
  mkdir /home/robot/ && \
  chown -R robot:robot /home/robot
  
USER robot

RUN\
  echo "Priming caches for sbt projects" &&\
  mkdir /tmp/sbt &&\
  cd /tmp/sbt &&\
  mkdir -p project src/main/scala &&\
  touch src/main/scala/scratch.scala &&\
  echo "sbt.version=0.13.11" > project/build.properties &&\
  sbt ++2.11.8 clean updateClassifiers compile &&\
  rm -rf /tmp/sbt

# Ensime and deps
RUN\
  echo "*** compiling ensime-server to cache resources"&&\
  cd /home/robot &&\
  git clone https://github.com/ensime/ensime-server.git &&\
  cd ensime-server &&\
  git reset --hard origin/1.0 &&\
  git clean -xfd &&\
  sbt ++2.11.8 ensimeConfig compile &&\
  rm -rf /home/robot/ensime-server

RUN\
  echo "sbt 1.0.2-SNAPSHOT" &&\
  mkdir -p /tmp/sbt-code &&\
  cd /tmp/sbt-code &&\
  git clone -b servermode https://github.com/rorygraves/sbt.git &&\
  cd /tmp/sbt-code/sbt &&\
  sbt publishLocal &&\
  cd / &&\
  rm -rf /tmp/sbt-code

# Define working directory.
WORKDIR /data

USER root

# Define default command.
CMD bash
