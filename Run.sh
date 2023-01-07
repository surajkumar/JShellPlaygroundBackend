#!/bin/bash
docker run --rm -i --init --cap-drop=ALL --network=none --pids-limit=2000 --memory=500M --read-only --name "hello-world" jshellwrapper