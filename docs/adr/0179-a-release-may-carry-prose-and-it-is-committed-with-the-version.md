# ADR-0179: A release may carry prose, and it is committed with the version

- **Status**: Accepted
- **Date**: 2026-09-22
- **Ticket**: [What the sign-in release publishes, the number it lands on, and the hand check before the tag](https://github.com/nodqora/nodqora/issues/190)

## Context

[ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)'s
task creates the GitHub Release with `--generate-notes` and nothing else. That
produces a list of merged pull requests and no prose. The comment beside the call
said the prose a stranger reads is the install documentation, and the release owns
none.

[ADR-0173](0173-an-install-without-sign-in-configured-is-closed-and-open-is-declared.md)
breaks that. Every upgrade from `v0.2.0` stops serving data until one key is
added, and its Consequences say **the release notes and `docs/install.md` owe that
sentence**. An operator who reads the Release page and pulls finds a closed
install, with the reason for it in a document they had no cause to open.

## Decision

**The release task passes `docs/releases/v<version>.md` to `gh release create` as
`--notes-file` when that file exists.** gh puts it above the generated list, so
the Release reads prose first and the list of changes after.

- **The file is optional.** A patch release is drop-in by ADR-0153 and usually
  owes no sentence, so a missing file changes nothing.
- **It is committed in the release pull request**, beside the version bump. The
  sentence is reviewed together with the number that makes it necessary.
- **It is published in the same `gh` call as the tag.** No window exists in which
  the Release is out without its prose, and nobody has to remember to edit it
  afterwards.
- **`docs/install.md` stays the lasting home.** The notes state what this upgrade
  demands and link to install.md *at the tag* for how to do it.

Rejected:

- **Editing the Release after the tag.** It needs no code change, but the Release
  is public without the sentence until someone edits it, and the next minor that
  owes one depends on someone remembering.
- **Leaving it to `docs/install.md`**, and amending ADR-0173. The Release page is
  where an upgrader looks, and a stranger has no reason to reread install
  documentation for an install that already works.

## Consequences

- **`docs/releases/` accumulates one file per release that needs prose.** They are
  historical once tagged and never edited, like the tags themselves.
- **A minor release that owes a sentence and ships without the file still
  releases.** Nothing enforces the file. ADR-0153's minor means *may* need an edit,
  and the task cannot tell whether one does.
