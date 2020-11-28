(ns kalai.pass.kalai.a-annotate-ast
  (:require [meander.strategy.epsilon :as s]
            [meander.epsilon :as m]
            [clojure.tools.analyzer.ast :as ast]
            [kalai.util :as u]
            [kalai.types :as types]
            [clojure.tools.analyzer.passes.jvm.emit-form :as e])
  (:import (clojure.lang IMeta)))

(defn resolve-in-ast-ns
  "Tools analyzer does not evaluate metadata in bindings or arglists.
   They are symbols which we can resolve to vars."
  [sym ast]
  (and
    (symbol? sym)
    (binding [*ns* (-> ast :env :ns find-ns)]
      (resolve sym))))

(defn resolve-kalias
  "We replace type aliases with their definition.
   We matched an AST node with meta data {:t T} where T is a var
   with meta {:kalias K}."
  [sym ast]
  (when-let [z (resolve-in-ast-ns sym ast)]
    (when (var? z)
      (:kalias (meta z)))))

(defn resolve-tag
  "We didn't find a t, so we resolve tag and convert it to a Kalai type."
  [sym ast]
  (or (get types/primitive-symbol-types sym)
      (when-let [c (resolve-in-ast-ns sym ast)]
        (when (class? c)
          ;; Clojure macro expands expressions out that create bindings,
          ;; and some of those will have unexpected types that we can just ignore
          (get types/java-types c)))))

;; TODO: what about type aliases in type aliases
(defn resolve-t [x ast]
  (let [{:keys [t tag]} (meta x)]
    (if t
      (if (symbol? t)
        (resolve-kalias t ast)
        t)
      (resolve-tag tag ast))))

(def ref-vars
  #{#'atom
    #'ref
    #'agent})

(defn ast-t [ast]
  (m/rewrite ast
    ;; (atom x)
    {:op   :invoke
     :fn   {:var (m/pred ref-vars)}
     :args [?value . _ ...]}
    ~(ast-t ?value)

    ;; ^{:t :long, :tag Long} x
    {:op   :with-meta
     :meta {:form {:t ?t :tag ?tag}}
     :expr  ?expr}
    ~(or ?t
         (get types/java-types ?tag)
         (ast-t ?expr))

    {:op :local
     :form ?form}
    ~(resolve-t ?form ast)

    ;; Last resort: 1
    {:op  :const
     :val ?val}
    ~(get types/java-types (type ?val))

    ;; Last resort: Clojure type inferred
    {:o-tag (m/pred some? ?o-tag)}
    ~(or (get types/java-types ?o-tag)
         ;; TODO: breaking the rules for interop...
         ;; is this a bad idea?
         ;; note that some o-tags are unhelpful,
         ;; like PersistentMap
         (#{StringBuffer} ?o-tag))

    ?else
    nil))

(defn t-from-meta [x]
  (:t (meta x)))

(defn propagate-ast-type [from-ast to-imeta]
  (if (and (instance? IMeta to-imeta)
           (not (t-from-meta to-imeta)))
    (u/maybe-meta-assoc to-imeta :t (ast-t from-ast))
    to-imeta))

(defn set-coll-t [val t]
  (m/rewrite t
    {(m/pred #{:mmap :map}) [?kt ?vt]}
    ;;->
    ~(into (u/maybe-meta-assoc (empty val)
                               :t (or (:t (meta val)) t))
           (for [[k v] val]
             [(set-coll-t k ?kt) (set-coll-t v ?vt)]))

    {(m/pred #{:mvector :vector :mset :set}) [?it]}
    ;;->
    ~(into (u/maybe-meta-assoc (empty val)
                               :t (or (:t (meta val)) t))
           (for [x val]
             (set-coll-t x ?it)))

    ?else
    ~val))

;; form is used for emit on map literals and constants,
;; except if they are with-meta expressions
;; eg: ^{:t {:map [:long :long]} {1 1}   <- with-meta
;; vs {1 1}     <- const
;; vs {1 (inc 1)}   <- map
(defn set-t-of-init
  "We match against both the collection and the type because maps must have
  a valid map type and we need the key value sub-types."
  [ast t]
  (m/rewrite [ast t]
    ;; collections [] #{} {} ()
    [{:op   :const
      :form (m/pred coll? ?form)
      &     ?more}
     (m/and {?k ?v} ?t)]
    ;;->
    {:op   :const
     :form ~(if ?t
              (set-coll-t ?form ?t)
              ?form)
     &     ?more}

    [{:op   :with-meta
      :expr ?expr
      &     ?more}
     ?t]
    ;;->
    {:op :with-meta
     ;; TODO: only if we don't have a t
     :expr ~(set-t-of-init ?expr ?t)
     & ?more}

    [{:op   :map
      :form ?form
      :keys [!keys ...]
      :vals [!vals ...]
      &     ?more}
     (m/and
       {(m/pred #{:mmap :map}) [?kt ?vt]}
       ?t)]
    ;;->
    {:op   :map
     :form ~(set-coll-t ?form ?t)
     :keys [(m/app set-t-of-init !keys ?kt) ...]
     :vals [(m/app set-t-of-init !vals ?vt) ...]
     &     ?more}

    [{:op    (m/pred #{:set :vector} ?op)
      :form ?form
      :items [!items ...]
      &      ?more}
     (m/and
       {(m/pred #{:mvector :vector :mset :set}) [?it]}
       ?t)]
    ;;->
    {:op    ?op
     :form ~(set-coll-t ?form ?t)
     :items [(m/app set-t-of-init !items ?it) ...]
     &      ?more}

    ;; else return ast unchanged
    [?ast ?t]
    ?ast))

(defn normalize-t-in-ast
  "Normalizing t consists of:
  1. If t is a valid Kalai type, it must be used.
  2. If t is a var, look up the kalias, which must be a Kalai type.
  3. If there is a tag, convert it to a Kalai type, and use that as t.
  4. If there is initialization, use the normalized initialization t.
  5. For initializations, if no t is present, use the binding t if present."
  [ast]
  (m/rewrite ast
    ;; (def x) and (def x 1)
    (m/and
      {:op   :def
       :name ?name
       :init ?init
       &     ?more
       :as   ?ast}
      (m/let [?t (resolve-t ?name ?ast)
              ?init-t (ast-t ?init)])
      (m/guard (or ?t ?init-t)))
    ;;->
    {:op   :def
     :name ~(u/maybe-meta-assoc ?name :t (or ?t ?init-t))
     :init ~(when ?init
              (set-t-of-init ?init (or ?init-t ?t)))
     &     ?more}

    ;; [x 1]
    (m/and
      {:op   :binding
       :form ?form
       :init ?init
       &     ?more
       :as   ?ast}
      (m/let [?t (resolve-t ?form ?ast)
              ?init-t (ast-t ?init)])
      (m/guard (or ?t ?init-t)))
    ;;->
    {:op   :binding
     :form ~(u/maybe-meta-assoc ?form :t (or ?t ?init-t))
     :init ~(set-t-of-init ?init (or ?init-t ?t))
     &     ?more}

    ;; ([x y z])
    {:op   :fn-method
     :form (?params & ?body)
     &     ?more
     :as   ?ast}
    ;;->
    {:op   :fn-method
     :form (~(u/maybe-meta-assoc ?params :t (resolve-t ?params ?ast))
             & ?body)
     &     ?more}

    ;; otherwise leave the ast as is
    ?else
    ?else))

(def propagate-types-from-bindings-to-locals
  "We propagate type information which is stored in metadata
  from the the place where they are declared on a symbol
  to all future usages of that symbol in scope."
  ;; TODO: function call type inference would be nice
  (s/rewrite
    ;; TODO: this must happen after value->binding
    ;; locals are usages of a declared binding
    {:op   :local
     :form ?symbol
     :env  {:locals {?symbol {:form ?symbol-with-meta
                              :init ?init}}
            :as     ?env}
     &     ?more}
    ;;->
    {:op   :local
     :form ~(propagate-ast-type ?init ?symbol-with-meta)
     :env  ?env
     &     ?more}

    ;; TODO: what about globals?

    ;; otherwise leave the ast as is
    ?else
    ?else))

(def erase-type-aliases
  "Takes a vector of ASTs,
  matches and removes kalias defs, leaves other ASTs alone."
  (s/rewrite
    [(m/or {:op   :def
            :meta {:form {:kalias (m/pred some? !kalias)}}
            :name !name}
           !ast) ...]
    ;;->
    (!ast ...)

    ?else
    ~(throw (ex-info "ASTs" {:else ?else}))))

(def annotate-vars
  (s/rewrite
    ;; annotate vars with their var as metadata so they can be identified later in the pipeline
    {:op   :var
     :var  ?var
     :form ?form
     &     ?ast}
    ;;->
    {:op   :var
     :var  ?var
     :form ~(u/maybe-meta-assoc ?form :var ?var)
     &     ?ast}

    ;; otherwise leave the ast as is
    ?else
    ?else))

;; TODO: split this mini-pipeline into 3 passes under the ast folder
(defn rewrite
  "There is contextual information in the AST that is not available in s-expressions.
  The purpose of this pass is to capture that information and modify the s-expressions to contain what we need."
  [asts]
  (->> asts
       (erase-type-aliases)
       (map #(ast/prewalk % normalize-t-in-ast))
       (map #(ast/prewalk % propagate-types-from-bindings-to-locals))
       ;; TODO: this is here for a circular depedency,
       ;; between normalization and propagation,
       ;; but it doesn't solve [x 1, y x, z y] ...
       (map #(ast/prewalk % normalize-t-in-ast))

       (map #(ast/prewalk % annotate-vars))
       ;; TODO:
       ;; assert our invariant that everything has a type
       ;; separate pass on s-expressions

       ))
