
# Mulo

_A Somewhat Overly Simplistic Job Runner._

This started from my necessity to run stuff remote from a GitHub action and monitor its execution.

## Mulo Features

- Java service with no external dependencies besides the JVM itself
- Simple configuration file to define jobs and tweak logging
- Simple HTTP API with endpoints to...
  - Trigger execution of jobs
  - Query whether a job is still running or has stopped, plus its return code
  - Continuously pull the job output

## Design Goals

- Do one thing and do it well. The latter is yet to be determined.
- Be able to submit jobs and monitor their execution.
- As minimalist as possible. I would have coded this in Go or C to avoid the JVM, if I had the time.
- Just Java to avoid any additional runtime libraries.
- No logging framework. Get by with the ugly one that comes with the JVM.
- No serialization libraries. Return bare strings and be done with it.
- No HTTP library. Use the basic `HttpServer` that comes with the JVM (see "Caveats" below).
- No fancy configuration file. Work with basic Java properties files (see "Configuration" below).
- No job scheduling/gatekeeping/whatever. Run the job and let it make any decisions about its execution.

## Configuration

```properties
# The most basic server settings
mulo.api.host=localhost
mulo.api.port=8000

# Job definitions
# Starts with a comma- or whitespace-delimited list of names
mulo.jobs=job-a, job-b, job-c
# For each job...
# Specify the path to the program to run
mulo.job.job-a.run=/path/to/job-a
# Optional: Specify inputs received by the submit endpoint as POST variables
# to be passed along to the program as environment variables
mulo.job.job-a.inputs=param1, param2
# Optional: Specify environment variables from the server process  
# to be passed to the program. Anything not listed here is removed.
mulo.job.job-a.envs=env1, env2
# Optional: Specify a user to run the program using `sudo -u <user> <program>`
mulo.job.job-a.sudo.user=a-user-who-runs-job-a
# Other jobs definitions...
mulo.job.job-b.run=/path/to/job-b
mulo.job.job-c.run=/path/to/job-c

# Logging settings
# These can be tweaked as desired
# This is standard java.util.logging configuration
handlers=java.util.logging.ConsoleHandler
.level=INFO
java.util.logging.ConsoleHandler.level=FINE
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
java.util.logging.SimpleFormatter.format=%1$tF %1$tT %4$s %5$s%n
```

## Building

Build the distributable:
```shell
./gradlew distZip
```
The package will be placed in `app/build/distributions`.

## Run as Service

Make sure a JVM >= 21 is available.

Unpack the distribution somewhere, like `/opt/mulo`.

Create a configuration file somewhere, like `/opt/mulo/mulo.properties`.

Create a new service unit file, like `/etc/systemd/system/mulo.service`.

```ini
[Unit]
Description=Mulo Job Runner
After=network.target

[Service]
User=user
# Group=mygroup (optional)
WorkingDirectory=/opt/mulo
ExecStart=/opt/mulo/bin/mulo /opt/mulo/mulo.properties
Type=simple
Restart=always
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

Reload systemd.
```shell
sudo systemctl daemon-reload
```

Start the service.
```shell
sudo systemctl start mulo.service
```

Enable the service to start at boot.
```shell
sudo systemctl enable mulo.service
```

Check the service status and logs
```shell
sudo systemctl status mulo.service
sudo journalctl -u mulo.service -f
```

Control the service using standard systemctl commands.
```shell
sudo systemctl stop mulo.service
sudo systemctl restart mulo.service
```

## Usage

Assumptions:
- There's a job called `test` already defined.
- The job takes parameters `a` and `b`.
- Mulo is running on http://localhost:8000.

### Query Service Health

```http request
GET http://localhost:8000

HTTP/1.1 200 OK
Content-type: text/plain; charset=UTF-8
Content-length: 11

Mulo ready!
```

### Submit a Job

```http request
POST http://localhost:8000/submit/test

a=111&b=222

HTTP/1.1 202 Accepted
Content-type: text/plain; charset=UTF-8
Content-length: 36

00742d17-793c-48a3-a365-260c2ff12068
```

### Query Job Status

```http request
GET http://localhost:8000/status/00742d17-793c-48a3-a365-260c2ff12068

HTTP/1.1 200 OK
Content-type: text/plain; charset=UTF-8
Content-length: 7

running
```

```http request
GET http://localhost:8000/status/00742d17-793c-48a3-a365-260c2ff12068

HTTP/1.1 200 OK
Content-type: text/plain; charset=UTF-8
Content-length: 6

queued
```

```http request
GET http://localhost:8000/status/00742d17-793c-48a3-a365-260c2ff12068

HTTP/1.1 200 OK
Content-type: text/plain; charset=UTF-8
Content-length: 9

stopped 0
```

```http request
GET http://localhost:8000/status/00742d17-793c-48a3-a365-260c2ff12068

HTTP/1.1 200 OK
Content-type: text/plain; charset=UTF-8
Content-length: 97

failed java.io.IOException: Cannot run program "non-existent": error=2, No such file or directory
```

### Get Job Output

Getting the output for a queued job.
```http request
GET http://localhost:8000/output/00742d17-793c-48a3-a365-260c2ff12068

HTTP/1.1 204 No Content
Date: Fri, 13 Mar 2026 00:07:17 GMT
Content-type: text/plain; charset=UTF-8

```

Getting all available output for a running job.
```http request
GET http://localhost:8000/output/00742d17-793c-48a3-a365-260c2ff12068

HTTP/1.1 216 
Content-range: bytes 0/*
Transfer-encoding: chunked
Content-type: text/plain; charset=UTF-8

Job execution output...
```

Getting all available output for a running job from a starting position.
Only a starting position is recognized.
```http request
GET http://localhost:8000/output/00742d17-793c-48a3-a365-260c2ff12068
Range: bytes=100-

HTTP/1.1 216 
Content-range: bytes 100/*
Transfer-encoding: chunked
Content-type: text/plain; charset=UTF-8

Job execution output from position 100...
```

Getting the output for a failed job.
```http request
GET http://localhost:8000/output/00742d17-793c-48a3-a365-260c2ff12068
Range: bytes=100-

HTTP/1.1 200 OK
Content-type: text/plain; charset=UTF-8
Content-length: 90

java.io.IOException: Cannot run program "non-existent": error=2, No such file or directory
```

## Tracking a Job from a Script

This sample script takes two parameters: The Mulo endpoint and the job ID.
It will keep polling the job status every second and incrementally pull the job output until
the job stops. Finally, it will exit with the status code of the job.
```shell
#!/usr/bin/env bash
if [ $# -ne 2 ]; then
    echo "Usage: $(basename "$0") <mulo-endpoint> <job-id>"
    exit 1
fi
MULO=$1
JOB=$2
cnt=0
while true; do
  status_line=$(curl -s "$MULO/status/$JOB")
  read status rc <<< "$status_line"
  out=$(curl -sr $cnt- "$MULO/output/$JOB")
  len=${#out}
  cnt=$((cnt + len))
  echo -n "$out"
  if [ "$status" == "queued" ] || [ "$status" == "running" ]; then
    sleep 1
  elif [ "$status" == "failed" ]; then
    echo
    echo "Job $JOB failed: ${status_line:7}"
    exit 100
  else
    echo
    echo "Job $JOB completed with code $rc"
    exit "$rc"
  fi
done
```

This is the same script as above, but assuming Mulo is running behind a proxy requiring Basic auth. 
In this case, the script also requires a user and a password.
```shell
#!/usr/bin/env bash
if [ $# -ne 4 ]; then
    echo "Usage: $(basename "$0") <mulo-endpoint> <user> <password> <job-id>"
    exit 1
fi
MULO_HOST=$1
MULO_USER=$2
MULO_PASS=$3
JOB=$4
cnt=0
while true; do
  status_line=$(echo "-u $MULO_USER:$MULO_PASS" | curl -K- -s "$MULO_HOST/status/$JOB")
  read status rc <<< "$status_line"
  out=$(echo "-u $MULO_USER:$MULO_PASS" | curl -K- -sr $cnt- "$MULO_HOST/output/$JOB")
  len=${#out}
  cnt=$((cnt + len))
  echo -n "$out"
  if [ "$status" == "queued" ] || [ "$status" == "running" ]; then
    sleep 1
  elif [ "$status" == "failed" ]; then
    echo
    echo "Job $JOB failed: ${status_line:7}"
    exit 100
  else
    echo
    echo "Job $JOB completed with code $rc"
    exit "$rc"
  fi
done
```
## Running a Job From a GitHub Action

```yaml
jobs:
  run-something:
    runs-on: ubuntu-latest
    steps:
      - name: tell mulo to run something
        shell: bash
        run: |
          job=$(curl -s -X POST "${{ vars.MULO_HOST }}/submit/run-something" -u '${{ vars.MULO_USER }}:${{ secrets.MULO_PASSWORD }}' -d 'some=values')
          cnt=0
          while true; do
            status_line=$(curl -s "${{ vars.MULO_HOST }}/status/$job" -u '${{ vars.MULO_USER }}:${{ secrets.MULO_PASSWORD }}')
            read status rc <<< "$status_line"
            out=$(curl -sr $cnt- "${{ vars.MULO_HOST }}/output/$job" -u '${{ vars.MULO_USER }}:${{ secrets.MULO_PASSWORD }}')
            len=${#out}
            cnt=$((cnt + len))
            echo -n "$out"
            if [ "$status" == "running" ]; then
              sleep 1
            else
              echo
              echo "Job $job completed with code $rc"
              exit "$rc"
            fi
          done
```
## Caveats

The API implementation is primitive. For instance, the submit endpoint assumes `application/x-www-form-urlencoded` 
content type, but neither checks the `Content-Type` header nor supports any other input types. Also, all input values 
need to be analyzed to be sure they're all properly sanitized.

This is yet another job runner nobody (except myself) needed. Immature software can be dangerous.

## To Do

- Time outs.
- Make sure all inputs are sanitized.
- Add more tests.
- Improve error handling.

## Contributing

Any help will be appreciated.

## License

[MIT](LICENSE)
