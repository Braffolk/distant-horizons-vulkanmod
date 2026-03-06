#!/bin/sh

publish_version()
{
    if [[ "$2" == "all" || "$1" == "$2" ]]
    then
        docker run --name=dh-build-$1 --rm -v /${PWD}:/home/build -e MC_VER=$1 dh-eclipse-temurin
        cp ./fabric/build/libs/*$1.jar ./buildAllJars/fabric/
        cp ./forge/build/libs/*$1.jar ./buildAllJars/forge/
        cp ./Merged/*.jar ./buildAllJars/merged/
    fi
}


if [ -z "$1" ]
then
    echo "Build target is undefined! [all] [1.20.1] [1.19.4] [1.19.2] [1.18.2] [1.17.1] [1.16.5]"
    exit 1
fi

docker build --tag=dh-eclipse-temurin -q .

mkdir -p buildAllJars/fabric
mkdir -p buildAllJars/forge
mkdir -p buildAllJars/merged
publish_version 1.20.1 $1
publish_version 1.19.4 $1
publish_version 1.19.2 $1
publish_version 1.18.2 $1
publish_version 1.17.1 $1
publish_version 1.16.5 $1
