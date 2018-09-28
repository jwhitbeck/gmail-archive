# Save a GMail query as a local maildir

## Overview

A simple CLI tool to save a [GMail query][] as a local [maildir][].

There are two main use-cases:

1. Archive a conversation for offline access or backup.
2. Perform incremental backups of an open-ended query (e.g. all non-inbox emails)

Gmail-archive uses [Google Auth 2.0][] for authentication. On the first run, it will open your browser and ask
for permission to give your gmail-archive instance read-only access to to your Gmail account. Access and
refresh tokens are stored on disk, so this step isn't required on subsequent runs.

[Gmail query]: https://support.google.com/mail/answer/7190?hl=en
[maildir]: https://en.wikipedia.org/wiki/Maildir
[offlineimap]: http://www.offlineimap.org/
[Google Auth 2.0]: https://developers.google.com/identity/protocols/OAuth2InstalledApp

## Install

```bash
npm install -g gmail-archive
```

This will make the `gmail-archive` command available on your `PATH`. Use `gmail-archive --help` to see the available actions and options.

## Usage

### Archive a conversation

For example, let's say you want to archive the thread with funny gifs in April 2017 under the `~/mail/gifs2017` directory.

```bash
gmail-archive init \
  --dir ~/mail/gifs2017 \
  --query "has:attachment subject:gif" \
  --before 2017-05-01 \
  --after 2017-04-01 \
  --client-id <google_client_id> \
  --client-secret <google_client_secret>
```

See below for how to obtain Google API credentials. This will open an OAUTH window in your default browser and
cache your OAUTH tokens to disk

Then sync the maildir:

```bash
gmail-archive fetch --dir ~/mail/gifs2017 --verbose
```

The sync uses only atomic writes to the maildir and can be safely interrupted and resumed at any
time. Subsequent calls to `gmail-archive fetch` will only download messages that haven't already been
downloaded.

### Incremental backups of the GMail's "All Mail" folder

Let's use the gmail-archive cli to create a monthly maildirs that syncs all _archived_ (i.e., non-inbox
emails) from your Gmail. This will result in, e.g., the following maildir hierarchy.

```
<root>/
  2014.01/
    cur/
    new/
    tmp/
  2014.02/
    cur/
    new/
    tmp/
  ...
  2018.02/
    cur/
    new/
    tmp/p
```

First configure the archive.

```bash
gmail-archive init \
  --dir ~/mail/archive \
  --query "-label:inbox" \
  --period month \
  --client-id <google_client_id> \
  --client-secret <google_client_secret>
```

We are now ready to sync the archive. The first run will fetch all matching messages, and subsequent runs will
efficiently fetch only the new messages.


```bash
gmail-archive fetch --dir ~/mail/archive --verbose
```

## Obtaining Google API credentials

You need to register gcal-to-org as an application yourself to obtain a _Client ID_ and _Client secret_.

Go to the Google API Manager and create a new project under any name.

Within that project, enable the "GMail" API. There should be a searchbox where you can just enter those
terms.

In the sidebar, select "Credentials" and then create a new "OAuth Client ID". The application type is "Other".

You’ll be prompted to create a OAuth consent screen first. Fill out that form however you like.

Finally you should have a _Client ID_ and a _Client secret_. Provide these in the config below.


## Notes

This tool is in the early stages and addresses my particular needs. However, it should be easy to adapt it to
your use-case, and I would be happy to iterate on the design.

The fetch command's `--concurrency` option controls how many emails to download in parallel. The default
value, 10, provides an order-of-magnitude speedup over IMAP sync tools like [offlineimap][].

Due to limitations of Gmail's API, entire emails must be stored in memory before writing them to disk. Large
attachments can therefore cause the Node VM to run out of memory. Should you experience this, consider
increasing the size of Node's _old space_.

```
export NODE_OPTIONS=--max_old_space_size=4096
gmail-archive [OPTIONS]
```

## License

Copyright &copy; 2017-2018 John Whitbeck

Distributed under the Apache License Version 2.0.
