# Parcel Quotes

This fictional single-module library calculates parcel delivery quotes in cents.
QuoteService validates input through Parcel and delegates pricing to QuotePolicy.
It has no HTTP adapter; callers invoke the library directly.

## Decision: fixed pricing

Prices are fixed in code to keep quotes deterministic for this demonstration.
A parcel up to 1000 grams costs 500 cents; heavier parcels cost 900 cents.
Remote destinations add 300 cents. Weight must be positive.
Carrier integration and currency conversion are outside this library's scope.

## Evidence limitations

The included test defines an expected remote small-parcel price of 800 cents.
No test execution report, deployment manifest, or production telemetry is supplied.
This documentation does not establish that tests passed or a deployment exists.
