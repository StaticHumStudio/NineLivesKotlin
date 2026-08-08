# Data Safety Reference

This older path is intentionally kept as a pointer so release work does not fork into two conflicting Data Safety sheets again.

Use the active source of truth:

```text
../data-safety-reference.md
```

That file reflects the current release branch, the live privacy policy URL, optional crash reports, optional manual feedback reports, Audiobookshelf server mode data, and the current HTTP encryption caveat.

---

## 2.1.0 assessment: Play Billing

Reassessed for the free + unlock release, with a Billing SKU in the picture.

**Expected delta: no new data types.** Google runs the purchase flow end to end
and the app never touches payment data, card details, or billing addresses. Play
Billing returns a purchase token and an acknowledgement state, both of which stay
on device in `nine_lives_entitlement_cache` and are excluded from backup.

Nothing here collapses or softens an existing disclosure. The source of truth
already answers **Yes** to collect-or-share, covering Audiobookshelf server-mode
traffic, optional crash reports, and optional emailed feedback. Every one of
those Yes answers stands. The framing to avoid is "does *no data collected*
survive Billing", because this app never claimed that and answering it would
produce a wrong declaration.

**Still to confirm against the actual Console form before submission**, since the
form's categories are the only authority on this:

- [ ] Billing introduces no new declarable data type on the current form
- [ ] The purchase token is not treated as a user or device identifier
- [ ] Update the Last-updated line in the canonical reference once confirmed

## 2.1.0 assessment: SB 2420

Re-read with an in-app purchase present. The low-exposure argument rests on the
app collecting no age signal, running no ad network, and operating no first-party
server, and a one-time unlock changes none of those. The purchase is between the
user and Google.

The one thing that would change the analysis is any future attempt to segment or
price by age, which is not on the roadmap and should reopen this section if it
ever is.
