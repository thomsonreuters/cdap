.. meta::
    :author: Cask Data, Inc.
    :description: Introduction to the Cask Data Application Platform
    :copyright: Copyright © 2014-2015 Cask Data, Inc.

:hide-relations: true

:hide-global-toc: true

.. _documentation-index:

==================================================
CDAP Documentation v\ |version|
==================================================

.. .. rubric:: Introduction to the Cask Data Application Platform

The Cask |(TM)| Data Application Platform (CDAP) is an integrated, open source application
development platform for the Apache Hadoop |(R)| ecosystem that provides developers with data and
application abstractions to simplify and accelerate application development, address a
broader range of real-time and batch use cases, and deploy applications into production
while satisfying enterprise requirements.

CDAP is a layer of software running on top of Hadoop platforms such as
the Cloudera Enterprise Data Hub, the Hortonworks |(R)| Data Platform, or 
the MapR Distribution. CDAP provides these essential capabilities:

- Abstraction of data in the Hadoop environment through logical representations of underlying
  data;
- Portability of applications through decoupling underlying infrastructures;
- Services and tools that enable faster application creation in development;
- Integration of the components of the Hadoop ecosystem into a single platform; and
- Higher degrees of operational control in production through enterprise best practices.

CDAP exposes developer APIs (Application Programming Interfaces) for creating applications
and accessing core CDAP services. CDAP defines and implements a diverse collection of
services that support applications and data on existing Hadoop infrastructure such as
HBase, HDFS, YARN, MapReduce, Hive, and Spark.

These documents are your complete reference to the Cask Data Application Platform: they help
you get started and set up your development environment; explain how CDAP works; and teach
how to develop and test CDAP applications.

It includes the CDAP programming APIs and client interfaces, with instructions
on the installation, monitoring and diagnosing fully distributed CDAP in a Hadoop cluster.

.. tabbed-block::
  :tabs: "Table of Contents","For Users/Data Scientists","For Developers","For Architects","For Admin/Ops"

  .. tabbed-block "Table of Contents"
  
  .. include:: table-of-contents.txt

  .. tabbed-block "Users/Data Scientists"
  
  .. include:: table-of-contents.txt
      :start-after: .. introduction
      :end-before: .. developers-manual
  
  .. tabbed-block "Developers"
  
  .. include:: table-of-contents.txt
      :start-after: .. developers-manual
      :end-before: .. cdap-apps

  .. tabbed-block "Architects"
  
  .. include:: table-of-contents.txt
      :start-after: .. cdap-apps
      :end-before: .. admin-manual

  .. tabbed-block "Admin/Ops"

  .. include:: table-of-contents.txt
      :start-after: .. admin-manual
      :end-before: .. integrations

