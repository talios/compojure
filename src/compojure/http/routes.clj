;; Copyright (c) James Reeves. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
;; can be found in the file epl-v10.html at the root of this distribution. By
;; using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

;; compojure.http.routes:
;; 
;; Macros and functions for compiling routes in the form (method path & body)
;; into stand-alone functions that return the return value of the body, or the
;; keyword :next if they don't match.

(ns compojure.http.routes
  (:use compojure.http.request)
  (:use compojure.str-utils)
  (:use compojure.control))

;; Functions for lexing a string

(defn- apply-action
  "Apply an action to the matcher if the action is not a function."
  [action matcher]
  (if (and (ifn? action)
           (not (keyword? action)))
    (action matcher)
    action))

(defn- lex-1
  "Lex one symbol from a string, and return the symbol and trailing source."
  [src clauses]
  (some
    (fn [[re action]]
      (let [matcher (re-matcher re src)]
        (if (.lookingAt matcher)
          [(apply-action action matcher)
           (.substring src (.end matcher))])))
    (partition 2 clauses)))

(defn- lex
  "Lex a string into tokens by matching against regexs and evaluating
   the matching associated function."
  [src & clauses]
  (loop [results []
         src     src
         clauses clauses]
    (if-let [[result src] (lex-1 src clauses)]
      (let [results (conj results result)]
        (if (= src "")
          results
          (recur results src clauses))))))

;; Functions for matching URIs using a syntax borrowed from Ruby frameworks
;; like Sinatra and Rails.

(defstruct uri-matcher
  :regex
  :keywords)

(defn compile-uri-matcher
  "Compile a path string using the routes syntax into a uri-matcher struct."
  [path]
  (let [splat   #"\*"
        word    #":([-\w]+)"
        literal #"[^:*]+"]
    (struct uri-matcher
      (re-pattern
        (apply str
          (lex path
            splat   "(.*?)"
            word    "([^/.,;?]+)"
            literal #(re-escape (.group %)))))
      (vec
        (remove nil?
          (lex path
            splat   :*
            word    #(keyword (.group % 1))
            literal nil))))))

;; Don't compile paths more than once.
(decorate-with memoize compile-uri-matcher)

(defn- assoc-keywords-with-groups
  "Create a hash-map from a series of regex match groups and a collection of
  keywords."
  [groups keywords]
  (reduce
    (partial merge-with
      #(if (vector? %1)
         (conj %1 %2)
         (vector %1 %2)))
    {}
    (map hash-map keywords (rest groups))))

(defn match-uri
  "Match a URI against a compiled matcher. Returns a map of keywords and
  their matching URL values."
  [uri-matcher uri]
  (let [matcher (re-matcher (uri-matcher :regex) (or uri "/"))]
    (if (.matches matcher)
      (assoc-keywords-with-groups
        (re-groups matcher)
        (uri-matcher :keywords)))))

;; Functions and macros for generating routing functions. A routing function
;; returns :next if it doesn't match, and any other value if it does.

(defn compile-route
  "Compile a route in the form (method path & body) into a function."
  [method path body]
  (let [matcher (if (string? path)
                  (compile-uri-matcher path)
                  `(compile-uri-matcher ~path))]
   `(fn [request#]
      (let [method# (request# :request-method)
            uri#    (request# :uri)]
        (if (or (nil? ~method) (= method# ~method))
          (if-let [~'route (match-uri ~matcher uri#)]
            (with-request-bindings request#
              ~@body)
            :next)
          :next)))))

(defmacro GET "Generate a GET route."
  [path & body]
  (compile-route "GET" path body))

(defmacro POST "Generate a POST route."
  [path & body]
  (compile-route "POST" path body))

(defmacro PUT "Generate a PUT route."
  [path & body]
  (compile-route "PUT" path body))

(defmacro DELETE "Generate a DELETE route."
  [path & body]
  (compile-route "DELETE" path body))

(defmacro HEAD "Generate a HEAD route."
  [path & body]
  (compile-route "HEAD" path body))

(defmacro ANY "Generate a route that matches any method."
  [path & body]
  (compile-route nil path body))

(defn combine-routes
  "Create a new route by combine a sequences of routes into one."
  [& routes]
  (fn [request]
    (loop [[route & routes] routes]
      (let [ret (route request)]
        (if (and (= ret :next) routes)
          (recur routes)
          ret)))))
