# Contract gaps

The previous round's generator lacked the manifest location, required top-level fields, and exact adapter policy field names. This frozen contract closes that input gap explicitly in C-1 and C-2; the earlier failure is not treated as independent behavioral evidence. No unresolved contract gaps remain in the scoped validation boundaries. Error ordering and unrelated malformed fields are not asserted.
