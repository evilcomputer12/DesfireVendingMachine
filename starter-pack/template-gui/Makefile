# The Midnight Brewer (GUI) -- project tasks
#
# Type `make` on its own to see this list.

PI        ?= martin@raspberrypi.local
REMOTE    ?= midnight-brewer
MAIN      ?= com.midnightbrewer.ui.MainApp

.DEFAULT_GOAL := help
.PHONY: help gui build test clean deps deploy deploy-full kiosk probe pi-setup ssh

help:                     ## show this help
	@echo "Midnight Brewer (GUI) -- available targets:"
	@echo
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "Target Pi: $(PI)   (override with: make kiosk PI=user@host)"

gui:                      ## run the kiosk in a WINDOW on this machine
	mvn -q javafx:run -Dbrewer.windowed=true

build:                    ## compile locally
	mvn -q compile

test:                     ## run the tests locally
	mvn test

clean:                    ## delete compiled output
	mvn -q clean

deps:                     ## copy dependency jars into target/dependency
	mvn -q dependency:copy-dependencies -DincludeScope=runtime

deploy:                   ## build + copy classes and resources to the Pi
	./scripts/deploy.sh

deploy-full:              ## also re-copy dependency jars (after pom.xml changes)
	./scripts/deploy.sh --full

kiosk:                    ## run fullscreen on the Pi's touchscreen
	./scripts/run-kiosk.sh

probe:                    ## RC522 wiring probe (needs Volume One's SpiProbe)
	./scripts/run.sh com.midnightbrewer.diag.SpiProbe

pi-setup:                 ## one-time Pi bootstrap (Java, SPI, groups, swap)
	scp scripts/pi-bootstrap.sh $(PI):~
	ssh $(PI) 'bash pi-bootstrap.sh'

ssh:                      ## open a shell on the Pi
	ssh $(PI)
