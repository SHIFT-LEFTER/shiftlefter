(ns shiftlefter.intent.shape
  "An intent element's declared content pattern (`:shape`, sl-ectw).

   A shape is a plain string of Java regex source, authored on the element
   that owns the text, and analyzed once at load:

   - it must compile;
   - it must have at least one capturing group (a constant readable is
     content, not a shape);
   - it may not contain a `{word}` binding token — the glossary is
     scenario-independent; `\\{word\\}` escapes a literal brace pair and
     `{6}`-style quantifiers are not tokens (they start with a digit);
   - a shape with exactly ONE group binds its value to the ELEMENT'S OWN
     NAME, so the scenario reads end to end without opening the intents
     file (`matching Reset.smsCode` produces `{smsCode}`). An unnamed single
     group is rewritten to `(?<elementName>…)` here, so everything
     downstream (the bindings lint, the exec normalizer, the stepdef
     bodies) sees an ordinary named-group regex. That element's name must
     therefore be binding-legal (`[a-z][A-Za-z0-9]*` — no hyphen or
     underscore), and a single group that IS named must carry the element's
     name (two names for one value is the defect this bead exists to kill);
   - a shape with several groups must name every one; those names are the
     tokens.

   JDK-only on purpose: `stepengine.bindings` depends on the intent layer
   (the matcher-slot classifier resolves refs through it), so this ns must
   not reach back into the engine."
  (:import [java.util.regex Pattern PatternSyntaxException]))

(def ^:private unescaped-token-re
  "A `{name(.path)*}` binding token not preceded by a backslash — the one
   thing a scenario-independent shape may not contain."
  #"(?<!\\)\{[A-Za-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)*\}")

(def ^:private binding-legal-name-re
  "An element name that can also be a binding name: lowerCamel, no
   hyphen/underscore (the Java named-group charset, lowercase start)."
  #"[a-z][A-Za-z0-9]*")

(defn binding-legal-name?
  "True when `element-name` (a string) can serve as a binding name."
  [element-name]
  (and (string? element-name)
       (some? (re-matches binding-legal-name-re element-name))))

(defn capturing-group-opens
  "Indexes of every CAPTURING group's open paren in regex `src`. Skips
   escapes (`\\(`), `\\Q…\\E` quoting, character classes (nested unions
   included, `[(]`), and the non-capturing `(?` family — except the named
   group `(?<name>`, which IS capturing and is distinguished from the
   lookbehinds `(?<=` / `(?<!`. Group COUNT is Pattern.groupCount's job;
   this only locates the parens."
  [^String src]
  (let [n (count src)
        ch (fn [i] (when (< i n) (.charAt src i)))]
    (loop [i 0, class-depth 0, quoted? false, acc []]
      (if (>= i n)
        acc
        (let [c (ch i) nxt (ch (inc i))]
          (cond
            quoted?
            (if (and (= c \\) (= nxt \E))
              (recur (+ i 2) class-depth false acc)
              (recur (inc i) class-depth true acc))

            (= c \\)
            (if (= nxt \Q)
              (recur (+ i 2) class-depth true acc)
              (recur (+ i 2) class-depth false acc))

            (pos? class-depth)
            (cond
              (= c \[) (recur (inc i) (inc class-depth) false acc)
              (= c \]) (recur (inc i) (dec class-depth) false acc)
              :else (recur (inc i) class-depth false acc))

            (= c \[)
            (recur (inc i) 1 false acc)

            (= c \()
            (let [capturing? (or (not= nxt \?)
                                 (and (= (ch (+ i 2)) \<)
                                      (not (contains? #{\= \!} (ch (+ i 3))))))]
              (recur (inc i) 0 false (if capturing? (conj acc i) acc)))

            :else
            (recur (inc i) class-depth false acc)))))))

(defn- err [type message element-name shape & [extra]]
  {:error (merge {:type type :message message :element element-name :shape shape}
                 extra)})

(defn analyze-shape
  "Analyze `shape` (regex source) declared on element `element-name` (a
   string). Returns

     {:ok {:source <authored> :pattern <effective source>
           :names #{kw …} :group-count n}}

   where `:pattern` differs from `:source` only when a single unnamed
   group was renamed to the element; or `{:error {:type :intent/… …}}`
   with one of:

   - :intent/invalid-shape            — not a string
   - :intent/shape-has-token          — an unescaped {word} inside
   - :intent/invalid-shape-pattern    — does not compile
   - :intent/shape-without-group      — zero capturing groups
   - :intent/shape-name-not-bindable  — single group, element name has - or _
   - :intent/shape-group-name-mismatch — single named group ≠ element name
   - :intent/shape-unnamed-groups     — several groups, not all named"
  [element-name shape]
  (cond
    (not (string? shape))
    (err :intent/invalid-shape
         (str ":shape on element " element-name
              " must be a string of regex source, got: " (pr-str shape))
         element-name shape)

    (re-find unescaped-token-re shape)
    (err :intent/shape-has-token
         (str ":shape on element " element-name " contains the binding token "
              (re-find unescaped-token-re shape)
              " — a shape is scenario-independent; write \\{…\\} for literal "
              "braces, or keep this pattern inline in the step")
         element-name shape {:token (re-find unescaped-token-re shape)})

    :else
    (let [compiled (try (Pattern/compile shape)
                        (catch PatternSyntaxException e e))]
      (if (instance? PatternSyntaxException compiled)
        (err :intent/invalid-shape-pattern
             (str ":shape on element " element-name " is not a valid regex: "
                  (.getMessage ^PatternSyntaxException compiled))
             element-name shape)
        (let [^Pattern p compiled
              group-count (.groupCount (.matcher p ""))
              names (set (map keyword (keys (.namedGroups p))))
              element-kw (keyword element-name)]
          (cond
            (zero? group-count)
            (err :intent/shape-without-group
                 (str ":shape on element " element-name " has no capture group"
                      " — a shape names the moving part of the text with "
                      "(…) or (?<name>…); constant text is content, not a shape")
                 element-name shape)

            (= 1 group-count)
            (cond
              (not (binding-legal-name? element-name))
              (err :intent/shape-name-not-bindable
                   (str "element " element-name " has a single-group :shape, so "
                        "its value binds to the element's own name — but \""
                        element-name "\" is not a legal binding name "
                        "(lowerCamel, no hyphen or underscore); rename the element")
                   element-name shape)

              (and (seq names) (not= names #{element-kw}))
              (err :intent/shape-group-name-mismatch
                   (str ":shape on element " element-name " names its one group "
                        (pr-str (name (first names))) " — a single group binds to "
                        "the element's own name; name it (?<" element-name ">…) "
                        "or leave it unnamed")
                   element-name shape {:group (first names)})

              (seq names)
              {:ok {:source shape :pattern shape :names names :group-count 1}}

              :else
              (let [opens (capturing-group-opens shape)]
                (if (= 1 (count opens))
                  (let [open (first opens)
                        pattern (str (subs shape 0 (inc open))
                                     "?<" element-name ">"
                                     (subs shape (inc open)))]
                    {:ok {:source shape :pattern pattern
                          :names #{element-kw} :group-count 1}})
                  (err :intent/invalid-shape-pattern
                       (str ":shape on element " element-name " — could not "
                            "locate its one capture group to name it; write it "
                            "as (?<" element-name ">…) explicitly")
                       element-name shape))))

            :else
            (if (= (count names) group-count)
              {:ok {:source shape :pattern shape :names names
                    :group-count group-count}}
              (err :intent/shape-unnamed-groups
                   (str ":shape on element " element-name " has " group-count
                        " groups but only " (count names) " named — a shape "
                        "with several groups must name every one with "
                        "(?<name>…); use (?:…) for grouping that binds nothing")
                   element-name shape))))))))
