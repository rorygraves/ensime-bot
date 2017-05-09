We have two subprojects right now:

webserver - The main Play web application - scalanator.io
scalarobot - The robot instance that will provide the docker based code capability.

N.b. Plan is to use a module published locally by webserver to as a library for scalarobot, this is because we need shared comms code, but Heroku can only have public dependencies outside of its own repo.  The link wont change very much but you would need to do (when read)


There is a few steps to get a full setup

# 1. Locally publish Ensime-client

checkout git@github.com:rorygraves/ensime-client.git
cd ensime-client
sbt clean publishLocal
// This publishes ensime-client 0.1-SNAPSHOT - this is a dependency of the robot (until this is completed and released as part of Ensime core)

# 2. Locally publish course-shared

Some modules are used as dependencies-artefacts, but they are not public at the moment.

cd .. # Project root
cd course-shared
sbt clean publishLocal

# 3. Build the basline robot docker image
cd .. # Project root
docker build -t scalanator/robotbase:v1 .

*Note* There is shared dependency between webserver and scalarobot wth the robotapi package, currently this is being manually copied when change.
Longer term this will be published by the server as an artifact for consumption by the robot

# 4a. Build and start the Robot - Option 1 - Docker

sbt docker:publishLocal # Creates scalanator-io-robot:1.0 artifact

To run for Webserver
docker run -it scalanator-io-robot:1.0  --serverHost www.scalanator.io --serverPort 443 --serverUseSSL true --secret "DX9PjS10vgjl" --workDir /opt/docker/workspace --robotName "Test1"

# 4b. Start the robot - Option 2 from IDE/sbt

build and run - arguments similar to above (localhost/9000/false

e.g. with create (will create workspace in /tmp/test1

--robotName "BobTheRobot" --serverHost localhost --serverPort 9000 --secret "XXXXX" --workDir /tmp/test1

e.g. Without create (if workspace in /tmp/test1 is missing will fail)

--create false --robotName "BobTheRobot" --serverHost localhost --serverPort 9000 --secret "XXXXX" --workDir /tmp/test1

You can also choose to not rebuild the .ensime/project folders each time (for fast restarts)  look for --create

# 5. Start webserver

In webserver directory:

sbt run -DROBOT_SECRET=XXXXXX

- go to localhost:9000

To run with local course - start SBT with:

```
  sbt -DFILESYSTEM_COURSE_ROOT=/workspace/scalanator.io/courses/
```
To run with courses directly from the repo, otherwise it will use the courses compiled into conf/course-NAME.zip as the course source.

# Deploying to heroku

Bit evil because we are pushing from a sub-folder - note the branch names 'webserver master' - if you need to push from another branch beware

  git push heroku `git subtree split --prefix webserver master`:master --force



