(ns shiftlefter.demo.fixture.reset-password
  "Reset-password page block for test fixture server.

   Two-step 2FA-style flow:

   1. GET /reset-password        — email entry form
   2. POST /reset-password       — look up user by email, generate a
                                    6-digit code, store it in the session,
                                    deliver it via the injected ISMS
                                    adapter's send!, redirect to the
                                    verify form.
   3. GET /reset-password/verify — code entry form
   4. POST /reset-password/verify — validate code against session state,
                                    render success or failure.

   Register with `:reset-password` in your test's `:pages` config. Requires
   that `:users` be in structured form (map with `:email` and `:phone`)
   and that `:sms` be an ISMS-implementing record (defaults to MockSMS
   when omitted by the fixture server)."
  (:require [clojure.string :as str]
            [shiftlefter.demo.fixture.handler :as handler]
            [shiftlefter.demo.fixture.pages :as pages]
            [shiftlefter.sms.protocol :as sms]))

;; ---------------------------------------------------------------------------
;; HTML templates
;; ---------------------------------------------------------------------------

(defn- email-form-html [error-message]
  (str "<!DOCTYPE html>
<html>
<head><title>Reset Password</title></head>
<body>
  <h1>Reset Password</h1>"
       (when error-message
         (str "<p id=\"error\" style=\"color: red;\">" error-message "</p>"))
       "
  <form method=\"POST\" action=\"/reset-password\">
    <div>
      <label for=\"email\">Email:</label>
      <input type=\"email\" id=\"email\" name=\"email\" required>
    </div>
    <div>
      <button type=\"submit\">Send Code</button>
    </div>
  </form>
</body>
</html>"))

(defn- verify-form-html [error-message]
  (str "<!DOCTYPE html>
<html>
<head><title>Verify Code</title></head>
<body>
  <h1>Enter Verification Code</h1>"
       (when error-message
         (str "<p id=\"error\" style=\"color: red;\">" error-message "</p>"))
       "
  <form method=\"POST\" action=\"/reset-password/verify\">
    <div>
      <label for=\"code\">Code:</label>
      <input type=\"text\" id=\"code\" name=\"code\" pattern=\"[0-9]{6}\" required>
    </div>
    <div>
      <button type=\"submit\">Verify</button>
    </div>
  </form>
</body>
</html>"))

(defn- success-html [username]
  (str "<!DOCTYPE html>
<html>
<head><title>Password Reset Verified</title></head>
<body>
  <h1>Verified</h1>
  <p id=\"verified\">Code verified for " username ".</p>
</body>
</html>"))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- parse-form-body [request]
  (when-let [body (:body request)]
    (let [body-str (if (string? body) body (slurp body))]
      (->> (str/split body-str #"&")
           (map #(str/split % #"=" 2))
           (filter #(= 2 (count %)))
           (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))
           (into {})))))

(defn- generate-code
  "Generate a 6-digit numeric code as a zero-padded string."
  []
  (format "%06d" (rand-int 1000000)))

(defn- store-reset-state! [session-atom session-id username code]
  (swap! session-atom assoc-in [session-id :reset] {:user username :code code}))

(defn- read-reset-state [session-atom session-id]
  (get-in @session-atom [session-id :reset]))

(defn- clear-reset-state! [session-atom session-id]
  (swap! session-atom update session-id dissoc :reset))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn- get-reset
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (email-form-html nil)))

(defn- post-reset
  [request session-atom users _behaviors & [{:keys [sms sms-from]}]]
  (let [{:keys [email]} (parse-form-body request)
        [username entry] (handler/find-user-by-email users email)]
    (if-not username
      (handler/html-response 200 (email-form-html "No user with that email"))
      (let [code (generate-code)
            phone (:phone entry)]
        (store-reset-state! session-atom (:session-id request) username code)
        (sms/send! sms {:from (or sms-from "+15550000000")
                        :to   phone
                        :body (str "Your verification code is: " code)})
        (handler/redirect-with-session "/reset-password/verify"
                                        (:session-id request))))))

(defn- get-verify
  [_request _session-atom _users _behaviors & _ctx]
  (handler/html-response 200 (verify-form-html nil)))

(defn- post-verify
  [request session-atom _users _behaviors & _ctx]
  (let [{:keys [code]} (parse-form-body request)
        session-id     (:session-id request)
        {:keys [user] expected-code :code} (read-reset-state session-atom session-id)]
    (cond
      (not expected-code)
      (handler/html-response 400 (verify-form-html "No reset in progress"))

      (= code expected-code)
      (do
        (clear-reset-state! session-atom session-id)
        (handler/html-response 200 (success-html user)))

      :else
      (handler/html-response 200 (verify-form-html "Invalid code")))))

;; ---------------------------------------------------------------------------
;; Page registration
;; ---------------------------------------------------------------------------

(pages/defpage :reset-password
  {:routes [["GET"  "/reset-password"        :get-reset]
            ["POST" "/reset-password"        :post-reset]
            ["GET"  "/reset-password/verify" :get-verify]
            ["POST" "/reset-password/verify" :post-verify]]
   :handlers {:get-reset   get-reset
              :post-reset  post-reset
              :get-verify  get-verify
              :post-verify post-verify}})
