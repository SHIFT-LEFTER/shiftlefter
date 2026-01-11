# Stealth Mode: Advanced Anti-Detection

This document covers advanced stealth techniques for bypassing bot detection systems.
ShiftLefter's built-in stealth flags (Tier 1) handle basic detection. This guide covers
Tier 2: ChromeDriver patching for sites with aggressive fingerprinting.

> **Note:** This document is for internal/private use. Do not include in public releases.

## Detection Tiers

### Tier 1: Built-in Stealth Flags (Automatic)

When you pass `{:stealth true}` to `init-persistent!`, ShiftLefter automatically applies:

```
--disable-blink-features=AutomationControlled
--excludeSwitches=enable-automation
--useAutomationExtension=false
```

This handles:
- `navigator.webdriver` property removal
- Chrome automation infobar suppression
- Basic automation extension detection

**Sufficient for:** Most sites, basic Cloudflare challenges, simple bot detection.

### Tier 2: ChromeDriver Patching (Manual)

ChromeDriver contains detectable signatures that sophisticated anti-bot systems look for.
The most common is the `$cdc_` prefix used for internal ChromeDriver variables.

**Required for:** Sites using advanced fingerprinting (Akamai, PerimeterX, DataDome,
aggressive Cloudflare configurations).

## Why Patching is Needed

ChromeDriver injects JavaScript variables into every page it controls. These variables
follow a predictable pattern:

```javascript
// ChromeDriver injects variables like:
$cdc_asdjflasutopfhvcZLmcfl_
$cdc_adoQpoasnfa76teletwp_
```

Anti-bot systems scan for variables matching the `$cdc_` prefix. Even with all other
stealth measures in place, this signature gives away automation.

### What Detection Looks Like

Sites may:
- Block the request entirely (403/captcha)
- Serve different content to detected bots
- Log the visit and flag the session for manual review
- Rate-limit aggressively

## How to Patch ChromeDriver

### Step 1: Locate Your ChromeDriver Binary

```bash
# macOS (if installed via Homebrew)
which chromedriver
# Usually: /usr/local/bin/chromedriver or /opt/homebrew/bin/chromedriver

# Or download from:
# https://googlechromelabs.github.io/chrome-for-testing/
```

### Step 2: Create a Patched Copy

**Important:** Never modify the original. Create a copy for ShiftLefter to use.

```bash
# Copy to a safe location
cp $(which chromedriver) ~/.shiftlefter/chromedriver-patched

# Make it executable
chmod +x ~/.shiftlefter/chromedriver-patched
```

### Step 3: Apply the Patch

The patch replaces `$cdc_` with a random string of the same length.

```bash
# Generate a random replacement (must be same length: 4 chars + underscore)
# Using a fixed replacement for reproducibility:
REPLACEMENT='$wdc_'

# Patch the binary (macOS/Linux)
perl -pi -e 's/\$cdc_/$wdc_/g' ~/.shiftlefter/chromedriver-patched
```

**Alternative using sed:**

```bash
# macOS sed requires backup extension
sed -i '' 's/\$cdc_/\$wdc_/g' ~/.shiftlefter/chromedriver-patched

# Linux sed
sed -i 's/\$cdc_/\$wdc_/g' ~/.shiftlefter/chromedriver-patched
```

### Step 4: Verify the Patch

```bash
# Should return nothing (no matches)
grep -a '$cdc_' ~/.shiftlefter/chromedriver-patched

# Should return matches with new prefix
grep -a '$wdc_' ~/.shiftlefter/chromedriver-patched
```

### Step 5: Configure ShiftLefter

Add to `~/.shiftlefter/config.edn`:

```clojure
{:chromedriver-path "/Users/yourname/.shiftlefter/chromedriver-patched"
 :default-stealth true}
```

Now all persistent subjects will use the patched ChromeDriver.

## Version Compatibility

ChromeDriver version must match your Chrome version (major version).

```bash
# Check Chrome version
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --version
# e.g., Google Chrome 120.0.6099.109

# Download matching ChromeDriver
# https://googlechromelabs.github.io/chrome-for-testing/
# Get the chromedriver for version 120.x.x.x
```

**Important:** When Chrome auto-updates, you may need to:
1. Download new ChromeDriver
2. Re-apply the patch
3. Update config if path changed

## What We Do NOT Provide

ShiftLefter does NOT:

1. **Ship patched binaries** — Legal and distribution concerns
2. **Auto-patch on install** — User must explicitly opt-in
3. **Provide evasion for specific services** — This is general-purpose guidance
4. **Guarantee bypass** — Detection is an arms race; no solution is permanent
5. **Support malicious use** — This is for legitimate testing and automation

## Troubleshooting

### "This version of ChromeDriver only supports Chrome version X"

ChromeDriver/Chrome version mismatch. Download the correct ChromeDriver version.

### Patch didn't work (still detected)

1. Verify patch applied: `grep -a '$cdc_' /path/to/chromedriver` should return nothing
2. Check you're using the patched binary (verify `:chromedriver-path` in config)
3. Some sites use additional detection beyond `$cdc_` — Tier 2 is not a silver bullet

### ChromeDriver crashes after patching

The patch may have corrupted the binary. Re-download and re-patch.

### Permission denied

```bash
chmod +x ~/.shiftlefter/chromedriver-patched
```

## References

- [undetected-chromedriver](https://github.com/ultrafunkamsterdam/undetected-chromedriver) — Python library with similar approach
- [Chrome for Testing](https://googlechromelabs.github.io/chrome-for-testing/) — Official ChromeDriver downloads
- [Puppeteer Stealth](https://github.com/berstend/puppeteer-extra/tree/master/packages/puppeteer-extra-plugin-stealth) — Alternative approach for Node.js

## Summary

| Tier | Effort | Coverage | How |
|------|--------|----------|-----|
| 1 | None | Basic detection | `{:stealth true}` |
| 2 | Manual patch | Advanced fingerprinting | Patch ChromeDriver + config |

For most use cases, Tier 1 is sufficient. Only apply Tier 2 when you encounter
specific detection issues that Tier 1 doesn't solve.
