# Find record candidates

Analyze `{{FILE}}` (or directory) for classes that could become JDK 16+ records.

## What makes a record candidate

- Small class that is essentially a data carrier.
- All fields are final (or should be).
- Only has a constructor, getters, and possibly `toString`/`equals`/`hashCode`.
- No mutation after construction (no setters).
- Does not extend another class (records can't extend, but can implement interfaces).
- Is not extended by other classes.

## Report format

For each candidate, report:

1. Full file path and class name.
2. Current fields (note which are final vs mutable).
3. Methods (getters, setters, other).
4. **Verdict**: CONVERT / SKIP with reason.
   - SKIP if: has setters, extends a class, is subclassed, has mutable state,
     has `destroy()` or mutation methods, implements `Serializable` with
     `serialVersionUID`.

## Do NOT make changes

This is an analysis-only prompt. Report findings but do not edit files.
