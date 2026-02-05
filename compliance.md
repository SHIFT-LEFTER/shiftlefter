```mermaid
flowchart LR
  A[Start: 46] --> B[After parse: 46, 100 pct]
  B --> C[After token: 46, 100 pct]

  C --> D[After AST: 46, 100.0 pct survive]
  C --> X[AST failures: 0, 0.0 pct of total]

  D --> E[After pickle: 46, 100.0 pct survive]
  D --> Y[Pickle failures: 0, 0.0 pct of total; 0.0 pct of reached]

  E --> Z[Full pass: 46, 100.0 pct]
```

```mermaid
pie showData
  title Outcomes (n=46)
  "Full pass (46, 100.0%)" : 46
  "AST failures (0, 0.0%)" : 0
  "Pickle failures (0, 0.0%)" : 0
```

# Compliance Report

## Summary

| Category | Count |
|----------|-------|
| Good files total | 46 |
| Parse errors | 0 |
| Token failures | 0 |
| AST failures | 0 |
| Pickle failures | 0 |
| **Full pass** | **46** |

| Category | Count |
|----------|-------|
| Bad files total | 11 |
| Correctly rejected | 11 |
| Should reject but didn't | 0 |

---

## Parse Errors - ALL PASS

---

## Token Failures - ALL PASS

---

## AST Failures - ALL PASS

---

## Pickle Failures - ALL PASS

---

## Bad Files - ALL REJECTED
