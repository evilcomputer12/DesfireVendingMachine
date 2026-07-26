# Appendix C — YouTube build plan

A production plan for filming this build: series structure, per-episode scripts, shot list, and the pre-flight checklist that stops a stream dying on air.

---

## C.1 The pitch

> **"I built a coffee machine that takes contactless payments — and I wrote every byte myself, from the SPI bus up. No libraries. Here's how the cryptography in your bank card actually works."**

**Why this works as content:** almost every NFC tutorial on YouTube is `import nfc; print(tag.uid)`. Nobody shows the anticollision tree walk, the mutual authentication, or the reason a debit and a commit are separate commands. You have a genuinely rare artefact: a from-scratch payment stack that *works*, with a physical payoff at the end.

**The hook is the money.** Not "let's learn OOP" — it's *"this card has €5 on it, and by the end of this video my code will take €1.50 off it."* Every episode should end with something visibly working.

---

## C.2 Series structure

Two formats, and you should decide up front which you're making:

| | **Edited series** | **Livestream** |
|---|---|---|
| Length | 10–25 min/ep | 90–120 min/ep |
| Audience | Discovery, evergreen | Existing community |
| Prep | High (script, retakes) | Low (but rehearse!) |
| Best for | Ep. 1, 6, 9 (the highlights) | The grind chapters |

**My recommendation: livestream the build, then cut a highlights series from the footage.** You get both, and the edited version benefits from you having already explained everything once.

### Episode map

| Ep | Title | Chapters | Payoff on screen | Length |
|---|---|---|---|---|
| **1** | *Talking to an NFC chip with zero libraries* | 00–02 | `version() = 0x92` | 18 min |
| **2** | *Waking up a contactless card* | 03 | `15/15 polls` | 15 min |
| **3** | *Reading a card's UID — the anticollision dance* | 04–05 | UID on screen | 20 min |
| **4** | *First words with a DESFire* | 06 | `GetVersion → …91AF` | 12 min |
| **5** | *Writing AES-CMAC from the RFC* | 07 | 4 vectors green | 22 min |
| **6** | ⭐ *Proving I know a secret without sending it* | 08–09 | `MUTUAL AUTH OK` | 25 min |
| **7** | ⭐ *Taking money off a real card* | 10 | `5.10 → 5.00 EUR` | 20 min |
| **8** | *Building the kiosk* | 11, 13 | Tap → brew | 18 min |
| **9** | *The phone that tops it up* | 12 | Balance goes up | 15 min |
| **10** | *What I'd do differently* | — | retrospective | 12 min |

⭐ = the two that will carry the series. Episode 6 is the one to put the most effort into: mutual authentication is genuinely beautiful and almost nobody explains it well on video.

---

## C.3 Episode scripts

### Structure for every episode

```
0:00  COLD OPEN    the payoff, out of order. 15 seconds, no talking head.
0:15  PROBLEM      what doesn't work yet, concretely
1:30  THEORY       the idea + the analogy (whiteboard or slide)
4:00  BUILD        live coding, the hard part
12:00 IT FAILS     ← keep this. Always keep this.
14:00 FIX          the diagnosis, out loud
16:00 PAYOFF       it works, on hardware
17:00 NEXT         one sentence teasing the next episode
```

> **Keep the failures.** This is the single most important production note. The debugging *is* the content — it's what separates you from a tutorial that hides the mess. When your MAC fails and you reason your way to "the counter increments before the response," that's the most valuable 90 seconds in the episode. Nobody learns from watching code work the first time, and nobody believes it either.

---

### Episode 1 — *Talking to an NFC chip with zero libraries*

**Cold open:** close-up of the card touching the reader, kiosk screen shows `€5.10 → €5.00`. Cut to black. *"By the end of this series, every byte of that transaction will be code I wrote. Today: making the chip say hello."*

**Beats:**
1. Show the RC522. €3. Show the datasheet — the register map page. *"This is all the documentation there is, and it's enough."*
2. Wire it up on camera. **Say the 3.3 V warning out loud** — it's the one mistake that costs a viewer their module.
3. The problem: *"I could plug this in and print things until it works. Two minutes per attempt, and when it fails I won't know if it's my code, my wiring, or the chip."*
4. **Analogy: the wall socket.** Draw it. Kettle → socket → power station | generator | bench supply.
5. Build `SpiLink`, `SpiException`, `FakeSpiLink`. Run the fake test green — **with no hardware plugged in.** Hold up the unplugged cable to camera. That visual sells the whole idea.
6. Build `Rc522Driver`. Explain the address encoding on screen: `((addr << 1) & 0x7E) | 0x80`, one bit at a time.
7. **Deliberate failure:** run it and get `-110`. *"That's the signed byte thing. Java has no unsigned types."* Fix with `& 0xFF`.
8. **Payoff:** on the Pi. `version() = 0x92`.

**Line to land:** *"That `0x92` means the chip is alive and I'm speaking its language. Nothing between my code and the silicon but two bytes of SPI."*

---

### Episode 5 — *Writing AES-CMAC from the RFC*

The one that proves the series is serious.

**Cold open:** scroll RFC 4493 on screen. *"I'm going to implement this. Not import it — implement it. And then prove it's correct."*

**Beats:**
1. What a MAC is. **Analogy: the wax seal.** Emphasise: it authenticates, it doesn't hide. Those are different jobs.
2. Why CMAC exists — the last-block problem with naive CBC-MAC, in one sentence.
3. Subkey generation on the whiteboard: `L = AES(K, 0)`, shift, conditional XOR with `0x87`.
4. Code `leftShift` live. **Get the carry direction wrong on purpose** (or genuinely — it's a common slip). All four vectors fail with plausible-looking garbage.
5. *"And this is why we don't test crypto on hardware. Look at that output — it's sixteen bytes of noise. Correct output is also sixteen bytes of noise. I cannot tell them apart by looking."* **This is the thesis of the whole series.** Deliver it slowly.
6. Fix: walk backwards, carry propagates up.
7. **Payoff:** four RFC vectors, green.

**Line to land:** *"Those four numbers were published in 2006 by people who've never seen my code. My code reproduces them exactly. That's not 'it seems to work' — that's proof."*

---

### Episode 6 — *Proving I know a secret without sending it* ⭐

The best episode. Take your time.

**Cold open:** *"This card and my Pi both know a 16-byte number. In the next 20 minutes they're going to prove it to each other — and if you record every bit of radio between them, you learn nothing."*

**Beats:**
1. Set up the problem properly. *"I can't send the key. I can't send a hash — you'd replay it. So how?"*
2. **Analogy: two spies.** Emphasise it must be **mutual** — one-way lets an impostor collect the passphrase.
3. Draw the full dance on the whiteboard. This is the money shot of the series — animate it in post if you can: RndB → rotate → RndA‖RndB' → TI‖RndA'.
4. The testability problem: *"This function calls a random number generator. So it does something different every time. How do I write a known-answer test for that?"* Pause on it — let the viewer feel the problem.
5. **Reveal `RandomSource`.** *"Randomness is a dependency like any other. Inject it, and I can pin it."*
6. Show the test class implementing **both** `RandomSource` and `ApduChannel`. *"My test is the fake card AND the fake dice."*
7. **Payoff, two stages** — this ordering matters:
   - offline vectors green (*"provably correct with no card in the room"*),
   - then the real card: `MUTUAL AUTH OK. TI = 8071F582`.
8. **Run it twice.** Different TI each time. *"That's not a replay of my test data. That's a live handshake with a real chip."* Best 20 seconds in the series.

---

### Episode 7 — *Taking money off a real card* ⭐

**Cold open:** the balance dropping. Full screen.

**Beats:**
1. Three requirements: authenticity, freshness, confidentiality. **Analogy: the cheque** — amount, signature, cheque number.
2. The counter, and the ordering rule. Say it three times, in three ways. It's the bug everyone hits.
3. Build `getValue`. **Deliberate:** forget the TI one-liner. Auth succeeds, `getValue` fails its MAC. Debug on camera — this is a *great* debugging sequence because the failure is one command away from its cause.
4. **The debit/commit split.** Stop and explain properly: *"Debit doesn't move the money. If the card leaves the field before the commit, nothing happened. That's not a limitation — that's the entire safety design."* Then physically **yank the card mid-transaction** and show the balance unchanged. Best demo in the series.
5. **Payoff:** `510 → 500 cents`, then tap the phone app and show the balance is genuinely 5.00 on an independent device.

**Line to land:** *"Every layer of that — the register writes, the anticollision, the CMAC, the session keys, the handshake, the secure messaging — I wrote. It took me [N] weeks and it moves real money."*

---

## C.4 Shot list

**Cameras:**
- **A** — screen capture, 1080p60 (OBS, capture the editor + a terminal)
- **B** — overhead on the bench: hands, reader, card. A phone on a gooseneck is fine.
- **C** *(optional)* — talking head for intros/outros

**Recurring shots you'll need repeatedly:**
- Card approaching the reader, slow, close (get 10 takes of this early — you'll reuse it constantly)
- Terminal output scrolling to a success line
- Whiteboard / iPad diagrams
- Insert of the datasheet register table
- The kiosk screen, straight on, no glare

**Audio:** a lav or a USB condenser. **Audio matters more than video** — viewers forgive 720p, they leave over hiss.

**Screen legibility:** editor at **16–18 pt minimum**, high-contrast theme, and **zoom in on the specific lines you're discussing** in post. Nobody can read your normal font size on a phone.

---

## C.5 Livestream pre-flight

Print this. Run it before every stream.

**Hardware, 30 min before:**
- [ ] Pi booted, on the network, IP noted (**static lease** — DHCP will move it mid-stream)
- [ ] `ssh` session open and tested
- [ ] RC522 responds: `version() = 0x92`
- [ ] Test card has **known balance** — set it deliberately with the phone app
- [ ] **A second card, provisioned identically**, in a drawer
- [ ] Reader taped down. It *will* get nudged on camera.

**Software:**
- [ ] `mvn clean compile` passes
- [ ] `mvn test` all green
- [ ] Vectors file open in a tab (you *will* be asked to show them)
- [ ] Git committed to a known-good point
- [ ] `git stash` any half-finished work

**The safety net — the most important item here:**
- [ ] A branch with the chapter **already working**, ready to `git checkout` if you get stuck live

That last one is not cheating. Streaming with no escape hatch is how you end up spending 40 minutes on a typo in front of an audience. Give yourself a hard rule: **stuck for 10 minutes on air → check out the working branch, walk through it, and move on.** You can debug it off-stream and cover the fix at the start of the next one.

**Stream ergonomics:**
- [ ] Notifications off, phone silent, second monitor off-camera for chat
- [ ] Water within reach
- [ ] Say what you're about to do **before** doing it — silent typing is dead air

---

## C.6 The honest bits — say these on camera

Credibility comes from what you're willing to admit. Three things worth saying out loud:

**1. The keys are hardcoded and that's not secure.** Say it plainly: *"Every card in my fleet has the same key, and that key is in the APK in plaintext. Anyone who pulls the APK can mint unlimited balance. For a bench project with test cards that's fine — I'm telling you so nobody copies this into something real."* Then explain diversification and HSMs briefly. Half your technical audience will already know; the ones who don't will learn the most important thing in the series.

**2. Cards can be bricked.** Warn viewers before the provisioning episode. Somebody *will* run it against their office badge.

**3. What you'd change.** Episode 10: the FSCI nibble bug that cost you an evening, the 3-second dead time from a bad wait loop, choosing wall-clock deadlines over iteration counts. A retrospective on your own mistakes is unusually good content — and it's the episode that proves you understand the thing rather than having merely typed it.

---

## C.7 Titles and thumbnails

**Titles that work:**
- *I wrote a contactless payment system from scratch (no libraries)*
- *How your bank card proves it knows a secret — without sending it*
- *Taking real money off an NFC card with code I wrote myself*

**Titles that don't:** anything starting with "Learning", "Tutorial", or "Part 3". Lead with the artefact.

**Thumbnails:** the card mid-tap with the balance visible, or a hex dump with one line circled. Avoid a face + arrow if you want the technical audience — they read it as low-signal.

**Description boilerplate:** link this cookbook, the repo, the RFC, and the NXP datasheet. Timestamp the chapters. Pin a comment with the BOM.

---

## C.8 Realistic schedule

| Phase | Effort |
|---|---|
| Rebuild solo from the book (no camera) | 3–5 evenings |
| Rehearse each episode's build once | 1 evening each |
| Record | 1 session each |
| Edit (edited series) | 3–4 h per finished episode |

**Do the full rebuild before filming anything.** Not because you'll forget — because the second time through, you'll know exactly where the interesting explanations live, and you'll have already hit the bugs you want to keep in.

---

*Back to [the index](README.md)*
