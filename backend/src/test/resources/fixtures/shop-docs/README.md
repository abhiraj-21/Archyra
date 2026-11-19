<h1 align="center"><img src="logo.png" alt="logo"></h1>

[![Build Status](https://travis-ci.org/example/sample-shop.svg)](https://travis-ci.org/example/sample-shop) [![Coverage](https://img.shields.io/badge/coverage-90-green.svg)](https://example.com)

# Sample Shop

Sample Shop is a tiny order service. It takes orders over HTTP, checks them, and keeps them in memory
until the process stops.

![architecture](docs/architecture.png)

## Modules

The web module receives requests, the service module places orders and the repository module stores them.
