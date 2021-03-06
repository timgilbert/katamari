(ns katamari.roll.extensions
  "The API by which to implement targets and participate in rolling."
  {:authors ["Reid 'arrdem' McKenzie <me@arrdem.com>"]}
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.tools.deps.alpha.extensions :refer [coord-type]]
            [katamari.roll.specs :as rs]))

;;; Manifests

(def ^{:doc "Multi-spec.

  Given a rule, being a list `(manifest & kvs)`, dispatch on the manifest to
  methods returning an `s/keys*` form for the remaining kvs. These kvs together
  with the manifest will define a build rule."
  :arglists '([rule-expr])}
  parse-manifest
  @#'rs/parse-manifest)

(defmacro defmanifest
  "Helper for defining rule manifests and their handling."
  [manifest-name keys-form]
  `(defmethod parse-manifest '~manifest-name [~'_]
     (s/and
      (s/cat :manifest (set ['~manifest-name])
             :kvs ~keys-form)
      (s/conformer
       (fn [v#]
         (if-not (= ::s/invalid v#)
           (merge (:kvs v#) (select-keys v# [:manifest]))
           ::s/invalid))))))

(s/fdef rule-manifest
  :args (s/cat :_ :katamari.roll.specs/rule)
  :ret :katamari.roll.specs/manifest)

(defn rule-manifest
  "Return a rule's manifest.

  Used to implement dispatch on rules by their manifests."
  [{:keys [manifest]}]
  manifest)

(defn- dispatch
  "Helper for the common pattern of dispatching on the rule manifest."
  ([config buildgraph target rule]
   (rule-manifest rule))
  ([config buildgraph target rule inputs]
   (rule-manifest rule))
  ([config buildgraph target rule products inputs]
   (rule-manifest rule)))

;;; Prep steps

(s/fdef manifest-prep
  :args (s/cat :conf any?
               :graph :katamari.roll.specs/buildgraph
               :manifest :katamari.roll.specs/manifest))

(defmulti
  ^{:arglists '([config buildgraph manifest])
    :doc "A task used to perform any required preparation for building a roll manifest.

Invoked once per roll for each unqiue manifest type in the buildgraph.

Implementations must return a pair `[config, buildgraph]` which may be updated.

For instance this task could check to see that some required program is present
in the filesystem or on the path."}

  manifest-prep
  (fn [_conf _graph manifest]
    manifest))

(s/fdef rule-prep
  :args (s/cat :conf any?
               :graph :katamari.roll.specs/buildgraph
               :target :katamari.roll.specs/target))

(defmulti
  ^{:arglists '([config buildgraph target rule])
    :doc "Perform any required preparation for building a target.

Invoked once per rule in the buildgraph, in topological order.

Implementations must return a pair `[config, buildgraph]`, which may be updated.

By default, tasks require no preparation."}

  rule-prep
  #'dispatch)

;;; The dependency tree

(s/fdef rule-inputs)

(defmulti
  ^{:arglists '([config buildgraph target rule])
    :doc "Return a map of keywords to sequences of depended targets.

This is used both to enumerate the dependencies of a rule for topological build
planning, and to define the keying of the inputs structure which the rule will
receive when built.

This allows tasks to take as inputs many different groups of dependencies,
without having to take separate steps to recover information about those deps."}

  rule-inputs
  #'dispatch)

(defmulti
  ^{:arglists '([config buildgraph target rule products inputs])
    :doc "Compute and return a content hash string for this build target.

The returned string is required to match `#\"[a-z0-9]{32,}\"`, must be
deterministic, cheap to compute and factor in the `rule-id` of its inputs as
well as the content of any files on the path.

Any change to your rule's inputs which would cause a different output to be
produced MUST cause the key returned by this function to change.

Invoked once per rule in the build graph, in topological order."}

  rule-id
  #'dispatch)

(defmulti
  ^{:arglists '([config buildgraph target rule products inputs])
    :doc "Apply the rule to its inputs, producing a build product.

Rule builds will execute with `#'raynes.fs/*cwd*` bound to a writable output
directory unique to this build rule by it's self-reported `rule-id`. Rules MUST
produce any file products which will be referred to in the build product into
this directory.

Rules are strongly discouraged from using say other tempdirs."}

  rule-build
  #'dispatch)
