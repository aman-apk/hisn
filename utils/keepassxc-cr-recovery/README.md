# hisn-cr-recovery

A small tool that helps you regain access to your Hisn password database in case you have it protected with YubiKey challenge-response and lost your key.
Currently supports KDBX4 databases with Argon2 hashing.

## Building

Tested with Go 1.13. Just run `go build`.

## Usage

What you need:
* your Hisn database
* your challenge-response secret. This cannot be retrieved from the YubiKey, it needs to be saved upon initial configuration of the key.

Then just run
```shell
keepass-cr-recovery path-to-your-password-database path-of-the-new-keyfile
```
It will prompt for the challenge-response secret. You will get a keyfile at the specified destination path. Then, to unlock your database in Hisn, you need to check "key file" instead of "challenge response" and load the file.
