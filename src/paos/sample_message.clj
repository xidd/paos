(ns paos.sample-message
  (:require [clojure.data.xml :as data-xml]
            [clojure.string :as string]
            [inflections.core :refer [plural]]
            [clojure.data.zip.xml :as data-zip-xml]
            [clojure.xml :as xml]
            [clojure.zip :as zip]))

(declare node->element)

(defn- tag-fix [tag-name]
  (cond-> tag-name
    (keyword? tag-name) name
    true                (string/split #":")
    true                last))

(defn- parse-comment [comment]
  (cond
    (string/starts-with? comment "Optional:")
    {:optional true}

    (re-matches #"type: ([a-zA-Z]+)$" comment)
    {:type (string/trim (second (re-matches #"type: ([a-zA-Z]+)$" comment)))}

    (string/starts-with? comment "Zero or more repetitions:")
    {:min-occurs 0}

    (re-matches #"([\d]+) or more repetitions:" comment)
    {:min-occurs (Integer/parseInt
                  (last (re-matches #"([\d]+) or more repetitions:" comment)))}

    (re-matches #"([\d]+) to ([\d]+) repetitions:" comment)
    (let [[_ min max] (re-matches #"([\d]+) to ([\d]+) repetitions:" comment)]
      {:min-occurs (Integer/parseInt min)
       :max-occurs (Integer/parseInt max)})

    (re-matches #"(type: ([a-zA-Z]+)|anonymous type) - enumeration: \[(.*)\]" comment)
    (let [matcher     (re-matches #"(type: ([a-zA-Z]+)|anonymous type) - enumeration: \[(.*)\]" comment)
          enumeration (mapv string/trim
                            (string/split (nth matcher 3)
                                          #","))
          type        (string/trim (or (nth matcher 2) "anonymous type"))]
      {:enumeration enumeration
       :type        type})

    :otherwise
    nil))

(defn- xml->map [root {:keys [path]}]
  (loop [fp (first path) rp (rest path) path-to-update []]
    (case fp
      0        (assoc-in {}
                         (map tag-fix (conj path-to-update (-> rp first plural keyword)))
                         (mapv #(xml->map % {:path (rest rp)})
                               (apply data-zip-xml/xml->
                                      root
                                      (conj path-to-update (first rp)))))
      :__attrs (assoc-in {}
                         (map tag-fix (conj path-to-update :__attrs (first rp) :__value))
                         (data-zip-xml/attr (if (not-empty path-to-update)
                                              (apply data-zip-xml/xml1->
                                                     root
                                                     path-to-update)
                                              root)
                                            (first rp)))
      :__value (assoc-in {}
                         (map tag-fix (conj path-to-update :__value))
                         (data-zip-xml/text
                          (apply data-zip-xml/xml1->
                                 root
                                 path-to-update)))
      (recur (first rp) (rest rp) (conj path-to-update fp)))))

(defn- deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn- deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
  only when there's a non-map at a particular level."
  [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (apply merge-with m maps)
       (apply f maps)))
   maps))

(defn- custom-merge [v1 v2]
  (cond
    (map? v1)    (deep-merge-with custom-merge v1 v2)
    (vector? v1) (vec (map-indexed (fn [idx v1]
                                     (deep-merge-with custom-merge
                                                      v1 (nth v2 idx)))
                                   v1))
    :otherwise   v2))

(defprotocol Element
  (get-original [this])
  (get-tag      [this])
  (get-fields   [this])
  (get-attrs    [this])
  (get-type     [this])
  (get-path     [this] [this fix-fn])
  (get-paths    [this] [this fix-fn] [this fix-fn path])
  (->mapping    [this] [this fix-fn])
  (->template   [this] [this root?])
  (->parse-fn   [this])
  (is-optional? [this])
  (is-array?    [this])
  (is-leaf?     [this])
  (is-enum?     [this]))

(defn- content->fields [content type]
  (let [content (filter #(not (string/starts-with? % "\n")) content)]
    (loop [el (first content) els (rest content) comments [] fields []]
      (if-not el
        fields
        (cond
          (string? el)
          {:__value nil
           :__type  type}

          (instance? clojure.data.xml.node.Comment el)
          (recur (first els) (rest els) (conj comments (:content el)) fields)

          :otherwise
          (recur (first els) (rest els) [] (conj fields (node->element el comments nil))))))))

(defn- string->stream
  ([s] (string->stream s "UTF-8"))
  ([s encoding]
   (-> s
       (.getBytes encoding)
       (java.io.ByteArrayInputStream.))))

(defn- node->element [{:keys [tag attrs content]
                       :or   {content '()
                              attrs   {}}}
                     comments
                     original-xml]
  (let [{:keys [type min-occurs max-occurs
                optional enumeration]} (into {} (map parse-comment comments))
        fields                         (content->fields content type)]
    (reify
      Element
      (get-original [_] original-xml)

      (get-tag [_] tag)

      (get-fields [_] fields)

      (get-attrs [_] attrs)

      (get-type [_] type)

      (get-path [this] (get-path this identity))
      (get-path [this fix-fn]
        (conj (if (is-array? this)
                [0]
                [])
              (fix-fn (get-tag this))))

      (get-paths [this] (get-paths this identity []))
      (get-paths [this fix-fn] (get-paths this fix-fn []))
      (get-paths [this fix-fn path]
        (let [fields         (get-fields this)
              this-path      (apply (partial conj path) (get-path this fix-fn))
              paths-to-attrs (map (fn [[attr-name attr-value]]
                                    (when (= attr-value "?")
                                      {:path (conj this-path :__attrs (fix-fn attr-name) :__value)}))
                                  (get-attrs this))]
          (filter identity
                  (concat paths-to-attrs
                          (if (is-leaf? this)
                            [{:path (conj this-path :__value)}]
                            (flatten
                             (if-not (map? fields)
                               (map (fn [c]
                                      (get-paths c fix-fn this-path))
                                    fields))))))))

      (->mapping [this] (->mapping this tag-fix))
      (->mapping [this fix-fn]
        (let [m ((if (is-array? this)
                   vector
                   identity)
                 {(fix-fn tag)
                  (merge (if-let [attrs (not-empty
                                         (->> attrs
                                             (filter (fn [[_ attrv]]
                                                       (= "?" attrv)))
                                             (map (fn [[attr-name _]]
                                                    [(fix-fn attr-name) {:__value nil
                                                                         :__type  "string"}]))
                                             (into {})))]
                           {:__attrs attrs}
                           {})
                         (apply merge
                                (-> this
                                    (get-fields)
                                    (->> (map (fn [c]
                                               (if (satisfies? Element c)
                                                 (->mapping c fix-fn)
                                                 (into {} [c]))))))))})]
          (if (vector? m)
            {(-> tag fix-fn plural) m}
            m)))

      (->template [this]
        (data-xml/indent-str
         (->template this true)))
      (->template [this root?]
        (let [tag (tag-fix (get-tag this))]
          (if root?
            (data-xml/element tag
                              attrs
                              (conj (into [(data-xml/cdata (str "{% with ctx=" tag " %}"))]
                                          (map (fn [c]
                                                 (if (satisfies? Element c)
                                                   (->template c false)))
                                               (get-fields this)))
                                    (data-xml/cdata "{% endwith %}")))
            [(when (is-optional? this)
               (data-xml/cdata "{% if ctx %}"))
             (when (is-array? this)
               (data-xml/cdata (str "{% for item in ctx." (plural tag) " %}")))
             (when-not root?
               (data-xml/cdata (str "{% with ctx=" (if (is-array? this)
                                                     (str "item." tag)
                                                     (str "ctx." tag))
                                    " %}")))
             (data-xml/element tag
                               (->> attrs
                                   (map (fn [[attr-name attr-value]]
                                          [attr-name (if (= attr-value "?")
                                                       (str "{{ctx.__attrs." (tag-fix attr-name) ".__value}}"))]))
                                   (into {}))
                               (let [fields (get-fields this)]
                                 (if (map? fields)
                                   (data-xml/cdata (str "{{ctx.__value}}"))
                                   (map (fn [c]
                                          (if (satisfies? Element c)
                                            (->template c false)
                                            (data-xml/cdata (str "{{ctx.__value}}"))))
                                        (get-fields this)))))
             (when-not root?
               (data-xml/cdata "{% endwith %}"))
             (when (is-array? this)
               (data-xml/cdata "{% endfor %}"))
             (when (is-optional? this)
               (data-xml/cdata "{% endif %}"))])))

      (->parse-fn [this]
        (fn [xml]
          (let [xml (-> xml string->stream xml/parse zip/xml-zip)]
            (apply deep-merge-with custom-merge
                   (map (partial xml->map xml)
                        (get-paths this))))))

      (is-optional? [_] (or optional (= min-occurs 0)))

      (is-array? [_] (boolean (or min-occurs (and max-occurs (> max-occurs 1)))))

      (is-leaf? [_] (or (empty? fields)
                        (contains? fields :__value)))

      (is-enum? [_] (boolean (not-empty enumeration))))))

(defn xml->element [msg]
  (node->element
   (data-xml/parse (java.io.StringReader. msg)
                   :namespace-aware false
                   :include-node? #{:element :characters :comment})
   []
   msg))
