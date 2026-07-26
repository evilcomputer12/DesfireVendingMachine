# The Midnight Brewer -- project tasks
#
# Type `make` on its own to see this list.

PI        ?= martin@raspberrypi.local
REMOTE    ?= midnight-brewer
MAIN      ?= com.midnightbrewer.ToolchainCheck

.DEFAULT_GOAL := help
.PHONY: help build test clean deps deploy deploy-full run probe pi-setup ssh

help:                     ## show this help
	@echo "Midnight Brewer -- available targets:"
	@echo
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "Target Pi: $(PI)   (override with: make deploy PI=user@host)"

build:                    ## compile locally
	mvn -q compile

test:                     ## run the tests locally
	mvn test

clean:                    ## delete compiled output
	mvn -q clean

deps:                     ## copy dependency jars into target/dependency
	mvn -q dependency:copy-dependencies -DincludeScope=runtime

deploy:                   ## build + copy classes to the Pi
	./scripts/deploy.sh

deploy-full:              ## also re-copy dependency jars (after pom.xml changes)
	./scripts/deploy.sh --full

run:                      ## run MAIN on the Pi (make run MAIN=com.foo.Bar)
	./scripts/run.sh $(MAIN)

probe:                    ## run the RC522 wiring probe on the Pi
	./scripts/run.sh com.midnightbrewer.diag.SpiProbe

pi-setup:                 ## copy + run the one-time Pi bootstrap script
	scp scripts/pi-bootstrap.sh $(PI):~
	ssh $(PI) 'bash pi-bootstrap.sh'

ssh:                      ## open a shell on the Pi
	ssh $(PI)
