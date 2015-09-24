Redlink Analysis Enhancement Engine [![Build Status](https://api.travis-ci.org/fusepoolP3/p3-stanbol-engine-redlink.svg)](https://travis-ci.org/fusepoolP3/p3-stanbol-engine-redlink)
===================================================

This module provides an
[Enhancement Engine](https://stanbol.apache.org/docs/trunk/components/enhancer/engine)
for [Apache Stanbol](https://stanbol.apache.org/) which allows you to
use [Redlink](http://dev.redlink.io/) to annotate your
text from Apache Stanbol.

This engine will call Redlink services. It will require you to register with Redlink and configure a analysis application. The engine needs to be configured with your app-id and the app-key.


Building and Running
====================

Building requires Maven 3, and running it requires Apache
Stanbol. Assuming a working installation is present, after downloading
the sources, switch to the sources root and run:

```sh
mvn package -DskipTests
```

This will produce an OSGi bundle under:

```sh
./target/stanbol-engines-redlink-[version].jar
```

which can be directly deployed into Apache Stanbol through its
configuration console.

Configuration Parameters
========================

The engine supports a number of configuration parameters, the most
relevant of which are:

The Engine support multiple configuration (OSGI configuration factory).

* __Engine Name__ _(stanbol.enhancer.engine.name)_: The name for the engine instance
* __Redlink App__ _(enhancer.engine.redlink.app)_: The Redlink analysis application name
* __Redlink Key__ _(enhancer.engine.redlink.key)_: The application key
* __Redlink Version__ _(enhancer.engine.redlink.version)_: the Redlink service version to use (optional)
