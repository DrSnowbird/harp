#!/bin/bash

if [ $# -ne 0 ]; then
    echo 'Usage: killhadoop.sh <app name>'
    exit -1
fi

name=`yarn application --list` 
names=(`echo $name | grep -Po "(application_[^\s]*) "`)

for name in ${names[*]}; do
    echo $name
    yarn application -kill $name
done
