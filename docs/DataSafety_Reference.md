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

Nothing here changes the standing declaration either. The live Console form
declares **No data collected / No data shared**, verified from the public store
page on 2026-08-24 (see the status header in the canonical reference). The Yes
analysis in that file was the June recommendation, never what shipped, and it
is kept as the fallback position, not the answer. The Billing conclusion holds
under either reading: the purchase flow adds no new data type, whether the form
says No (nothing to add) or the fallback Yes analysis ever gets invoked (no new
category joins it).

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
