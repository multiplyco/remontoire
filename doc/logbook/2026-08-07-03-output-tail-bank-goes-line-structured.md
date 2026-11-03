# 2026-08-07 — The output tail, and the bank goes line-structured

**Built (2026-08-07) — item (6) from the in-situ wishlist, taken structurally.** The question that shaped it: values
truncate safely because `(res id)` holds the whole, but the output bank keeps only a 32 KiB tail — would a tail
option lose the rest? No: `out` is a non-consuming read, so a window takes nothing from a later full read; the only
irretrievable drop is the cap's own, which predates the feature and stays announced. Henrik's structural call: bank
*entries*, not one string — retain or drop whole log lines, string manipulation only at the edges. So the bank became
a queue of completed lines plus the pending unterminated partial: trimming pops whole lines oldest-first (the one
surviving string cut is a single line outgrowing the entire cap), `tail` is a `subvec` window with what lies behind
announced (`…[6 more lines retained]`), and `deposit!` shed an accident the restructure exposed — the old
string-concat bank re-copied the whole retained 32 KiB on every write. The `since` cursor kept its character-count
contract through the restructure, tests unchanged and passing. Surfaced as `(__r/out id n)`, the pending line
counting as the newest entry — it is the progress line. Verified live: an 8-line chatty eval tailed to 3 showed the
last two lines plus the in-progress print with the window announced, the full read intact behind it. The reload
itself demonstrated the transition hazard on cue: the eval carrying the `:reload` deposited into a bank minted by
the old build and NPE'd — old-shape state meeting new code, machinery-subtracted frames pointing straight at it;
the next eval minted fresh and ran clean. Suite green: 74 tests, 266 assertions.

Revised the same day: the windowing arity lasted hours. Henrik's ergonomics question — wouldn't `out` returning the
vector compose better than `(out id n)`? — answered itself the moment it met the doctrine: results are plain data,
composition is the option surface, and `(out id n skip)` is exactly the per-function knob §2 rejects. So `out`
returns the vector of lines (terminators stripped, pending line last), `take-last` peeks, `drop-last` pages,
`filter` searches — verified live, all four idioms through the connection — and the drop announcement moved to the
vector's first element, positionally honest at the head end where tail-reaching composition never meets it. One
trim rule tightened in passing: a completed entry outgrowing the whole cap is now cut from its own front instead of
dropped whole — tail-keeping outranks entry purity when the two collide. Suite green: 75 tests, 268 assertions.
