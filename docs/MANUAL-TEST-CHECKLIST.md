# Manual Test Checklist for Persistent Subjects

Manual verification tests that cannot be reliably automated.

> **When to run:** Before releasing a version with changes to persistent subject functionality.

---

## Prerequisites

- [ ] Chrome installed and `chrome/find-binary` finds it
- [ ] ChromeDriver available at expected path (or configured)
- [ ] No existing test subjects (`(repl/list-persistent-subjects)` returns `[]`)

---

## Test 1: Sleep/Wake Session Recovery

**Purpose:** Verify browser commands work after laptop sleep/wake cycle.

**Steps:**

1. Start REPL:
   ```bash
   clj -A:dev
   ```

2. Create persistent subject:
   ```clojure
   (require '[shiftlefter.repl :as repl])
   (repl/init-persistent-subject! :sleep-test {:stealth true})
   ```

3. Navigate to a page:
   ```clojure
   (repl/as :sleep-test "opens the browser to 'https://example.com'")
   ```

4. Close laptop lid (or manually sleep)

5. Wait 30+ seconds

6. Wake laptop

7. Run another command:
   ```clojure
   (repl/as :sleep-test "should see {:text \"Example Domain\"}")
   ```

**Expected:** Command succeeds after brief reconnection delay (you may see a small pause).

**Cleanup:**
```clojure
(repl/destroy-persistent-subject! :sleep-test)
```

- [ ] **PASS** — Command succeeded after sleep/wake
- [ ] **FAIL** — Error: _______________

---

## Test 2: Chrome Process Death Recovery

**Purpose:** Verify Chrome auto-relaunches when killed externally.

**Steps:**

1. Create persistent subject:
   ```clojure
   (require '[shiftlefter.repl :as repl])
   (repl/init-persistent-subject! :kill-test {:stealth true})
   ```

2. Navigate to a page:
   ```clojure
   (repl/as :kill-test "opens the browser to 'https://example.com'")
   ```

3. Note the Chrome PID:
   ```clojure
   (repl/list-persistent-subjects)
   ;; Look for :pid in the output
   ```

4. Kill Chrome from terminal:
   ```bash
   kill <PID>
   ```

5. Verify Chrome window closed

6. Run a browser command:
   ```clojure
   (repl/as :kill-test "opens the browser to 'https://httpbin.org/get'")
   ```

**Expected:** New Chrome window opens, command succeeds.

**Cleanup:**
```clojure
(repl/destroy-persistent-subject! :kill-test)
```

- [ ] **PASS** — Chrome relaunched and command succeeded
- [ ] **FAIL** — Error: _______________

---

## Test 3: Stealth Mode Basic Validation

**Purpose:** Verify stealth flags are applied and basic bot detection is bypassed.

**Steps:**

1. Create stealth subject:
   ```clojure
   (require '[shiftlefter.repl :as repl])
   (repl/init-persistent-subject! :stealth-test {:stealth true})
   ```

2. Check navigator.webdriver flag:
   ```clojure
   ;; Navigate to a page that shows automation detection
   (repl/as :stealth-test "opens the browser to 'https://bot.sannysoft.com'")
   ```

3. Visually inspect the page:
   - Look for "webdriver" row
   - Should show "missing" or "false" (green), not "present" or "true" (red)

4. Optional: Test Cloudflare-protected site:
   ```clojure
   (repl/as :stealth-test "opens the browser to 'https://nowsecure.nl'")
   ```
   - Should not immediately show Cloudflare challenge
   - Note: This may still trigger on some sites depending on other factors

**Expected:** Basic automation detection shows as "not detected."

**Cleanup:**
```clojure
(repl/destroy-persistent-subject! :stealth-test)
```

- [ ] **PASS** — bot.sannysoft.com shows webdriver as undetected
- [ ] **FAIL** — Detection shows automation present

---

## Test 4: Profile Persistence

**Purpose:** Verify cookies and localStorage survive JVM restarts.

**Steps:**

1. Create persistent subject:
   ```clojure
   (require '[shiftlefter.repl :as repl])
   (repl/init-persistent-subject! :persist-test)
   ```

2. Navigate and set some state:
   ```clojure
   (repl/as :persist-test "opens the browser to 'https://example.com'")
   ```

3. In the browser, open DevTools (F12), go to Console, run:
   ```javascript
   localStorage.setItem('shiftlefter-test', 'hello-from-test-4');
   ```

4. Exit REPL completely (Ctrl+D or `(System/exit 0)`)

5. Start new REPL:
   ```bash
   clj -A:dev
   ```

6. Reconnect to subject:
   ```clojure
   (require '[shiftlefter.repl :as repl])
   (repl/connect-persistent-subject! :persist-test)
   ```

7. In the browser DevTools Console:
   ```javascript
   localStorage.getItem('shiftlefter-test')
   ```

**Expected:** Returns `"hello-from-test-4"` — localStorage persisted across JVM restarts.

**Cleanup:**
```clojure
(repl/destroy-persistent-subject! :persist-test)
```

- [ ] **PASS** — localStorage value preserved
- [ ] **FAIL** — Value was null/missing

---

## Test 5: Multiple Subjects

**Purpose:** Verify multiple subjects can run simultaneously without conflicts.

**Steps:**

1. Create two subjects:
   ```clojure
   (require '[shiftlefter.repl :as repl])
   (repl/init-persistent-subject! :multi-a)
   (repl/init-persistent-subject! :multi-b)
   ```

2. Verify different ports:
   ```clojure
   (repl/list-persistent-subjects)
   ;; Should show two subjects with different :port values
   ```

3. Navigate each to different pages:
   ```clojure
   (repl/as :multi-a "opens the browser to 'https://example.com'")
   (repl/as :multi-b "opens the browser to 'https://httpbin.org'")
   ```

4. Verify isolation:
   ```clojure
   (repl/as :multi-a "should see {:text \"Example Domain\"}")
   (repl/as :multi-b "should see {:text \"httpbin\"}")
   ```

**Expected:** Both pass — subjects are independent.

**Cleanup:**
```clojure
(repl/destroy-persistent-subject! :multi-a)
(repl/destroy-persistent-subject! :multi-b)
```

- [ ] **PASS** — Both subjects work independently
- [ ] **FAIL** — Error: _______________

---

## Summary

| Test | Status | Notes |
|------|--------|-------|
| 1. Sleep/Wake Recovery | | |
| 2. Chrome Death Recovery | | |
| 3. Stealth Mode | | |
| 4. Profile Persistence | | |
| 5. Multiple Subjects | | |

**Tested by:** _______________
**Date:** _______________
**Version:** _______________
