
Using Sparkta
*************

Installing Sparkta
==================

An easy way to test Sparkta is using it with the `sandbox <sandbox.html>`__.

If you prefer to install it by yourself you can install Sparkta by unpackaging a `release <https://github
.com/Stratio/sparkta/releases>`__ or by generating the deb or rpm packages from the `source code <https://github
.com/Stratio/sparkta>`__.

You can generate rpm and deb packages by running::

    mvn clean package -Ppackage

**Note:** you need to have installed the following programs in order to build these packages:

 * In a debian distribution:

  - fakeroot
  - dpkg-dev
  - rpm

 * In a centOS distribution:

  - fakeroot
  - dpkg-dev
  - rpmdevtools

.. _zookeeper-label:

Prerequisites
=============

Apache Zookeeper needs to be installed and run in the system beforehand. You can run it in the sandbox with the
following commands::

    cd /opt/sds/zookeeper
    sudo ./bin/zkServer.sh start


Running Sparkta
===============

Once Sparkta has been installed, to get it running you can use the following instructions:

 * You can run ``sh $SPARKTA_HOME/bin/run``. Default installation directory is ``/opt/sds/sparkta``::

      cd /opt/sds/sparkta

      sh bin/run


 * As a service::

      sudo service sparkta [start|stop|restart|status]

Policy
======

A policy it's a JSON document that define your aggregation rules. It's composed of:

* `Inputs <inputs.html>`__: where is the data coming from?
* `Transformations <transformations.html>`__ : do you want to enrich your data?
* `Cubes <cube.html>`__ : how do you want to aggregate your data?
* `Outputs <outputs.html>`__ : where aggregated data should be stored?

You can read more about policies `here <policy.html>`__


Submitting a Policy
===================

The policy must be submitted with the following syntax::

    curl -H "Content-Type: application/json" --data @PATH-TO-POLICY http://<SPARKTA-HOST>:<SPARKTA-PORT>/policyContext

Example::

    curl -H "Content-Type: application/json" --data @examples/policies/ITwitter-OMongo.json http://localhost:9090/policyContext


User interface
==============

You can use Sparkta with an user interface at the url http://localhost:9090


Cluster
======

Sparkta can be executed in cluster mode, in this mode you can run more than one policy in the same Spark cluster.
You can read more about cluster execution mode `here <cluster.html>`__


