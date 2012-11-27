;; The contents of this file are subject to the LGPL License, Version 3.0.

;; Copyright (C) 2012, Newcastle University

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.

;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see http://www.gnu.org/licenses/.

(ns owl.owl
  (:require [owl.util :as util])
  (:import
   (org.semanticweb.owlapi.model OWLOntologyManager OWLOntology IRI
                                 OWLClassExpression OWLClass OWLAnnotation
                                 OWLNamedObject)
   (org.semanticweb.owlapi.apibinding OWLManager)
   (org.coode.owlapi.manchesterowlsyntax ManchesterOWLSyntaxOntologyFormat)
   (org.semanticweb.owlapi.io StreamDocumentTarget OWLXMLOntologyFormat)
   (org.semanticweb.owlapi.util DefaultPrefixManager)
   (java.io ByteArrayOutputStream FileOutputStream PrintWriter)
   (java.io File)
   ()
   (org.semanticweb.owlapi.model AddAxiom RemoveAxiom AddImport)))



;; far as I can tell, we only ever need one of these.
(def
  ^{:doc "A java object which is the main factory for all other objects"
    :private true}
  ontology-data-factory
  (OWLManager/getOWLDataFactory))


(def
  ^{:doc "The OWLOntologyManager to use"
   }
  owl-ontology-manager
  (OWLManager/createOWLOntologyManager ontology-data-factory))

;; the current ontology provides our main mutable state. Strictly, we don't
;; need to do this, but the alternative would be passing in an ontology to
;; almost every call, or running everything inside a binding. Painful. 
(def
  ^{:dynamic true
    :doc
    "The currently bound ontology"
    }
  *current-bound-ontology* nil)

(def ^{:doc "Map between namespaces and ontologies"}
  ontology-for-namespace (ref {}))


(defrecord
   ^{:doc "Key data about an ontology.
iri is the IRI for the ontology
file is the location that it will be saved in
manager is an OWLOntologyManager from which the ontology comes
ontology is an object of OWLOntology.
"
      :private true
      }
    Ontology [iri prefix ontology])


(defrecord
    ^{:doc "Data about an ontology addition.

entity is the entity (class, property, etc) that was added to the ontology

axioms is a list of all the axioms that were used to add this entity. 
"
      :private true
      }
    AxiomedEntity [entity axioms]
    Object
    ;; prints out too much -- however this isn't working at the moment
    (toString [this]
      (format "AxiomedEntity( %s )" (:entity this))))


(defn named-object? [entity]
  (or (instance? AxiomedEntity entity)
      (instance? OWLNamedObject entity)))

(defn as-named-object [entity]
  (or
   (and (instance? AxiomedEntity entity)
        (:entity entity))
   (and (instance? OWLNamedObject entity)
        entity)
   (throw (IllegalArgumentException. "Expecting a named entity"))))

(defn generate-ontology [iri prefix jontology]
  (Ontology. iri prefix jontology))

(defn ontology [& args]
  (let [options (apply hash-map args)
        iri (IRI/create (:iri options))]
    ;; blitz existing ontology or we get breakages
    (when (.contains owl-ontology-manager iri)
      (.removeOntology
       owl-ontology-manager
       (.getOntology owl-ontology-manager iri)))
    (generate-ontology
     iri (:prefix options) 
     (.createOntology owl-ontology-manager iri))))

(defmacro defontology
  "Define a new ontology with `name'. 

The following keys must be supplied. 
:iri -- the IRI for the new ontology
:prefix -- the prefix used in the serialised version of the ontology
"
  [name & body]
  `(do
     (let [ontology# (ontology ~@body)]
       (def ~name ontology#)
       (owl.owl/ontology-to-namespace ontology#)
       ontology#
       )))

(defn ontology-to-namespace
  "Sets the current ontology as defined by `defontology'"
  [ontology]
  (dosync (ref-set
           ontology-for-namespace
           (merge @ontology-for-namespace
                  {*ns* ontology}))))

(defn get-current-ontology []
  "Gets the current ontology"
  ;; if current ontology is inside a binding
  (or *current-bound-ontology*
      ;; so use the namespace bound one
      (get @ontology-for-namespace *ns*)
      ;; so break
      (throw (IllegalStateException. "Current ontology has not been set"))))


(defn get-current-jontology[]
  "Gets the object representing the current ontology"
  (:ontology (get-current-ontology)))

(defn get-current-iri[]
  "Gets the current IRI"
  (let [iri (:iri (get-current-ontology))]
    (when (nil? iri)
      (throw (IllegalStateException. "Current ontology IRI has not been set")))
    iri))

(defn get-current-prefix []
  "Gets the current prefix"
  (let [prefix (:prefix (get-current-ontology))]
    (when (nil? prefix)
      (throw (IllegalStateException. "No current prefix")))
    prefix))


(defn save-ontology
  "Save the current ontology in the file returned by `get-current-file'.
or `filename' if given. 
"
  ([filename]
     (save-ontology filename (ManchesterOWLSyntaxOntologyFormat.)
                    (str "## This file was created by Clojure-OWL\n"
                         "## It should not be edited by hand\n" )))
  ([filename format]
     (save-ontology filename format ""))
  ([filename format prepend]
     (let [file (new File filename)
           output-stream (new FileOutputStream file)
           file-writer (new PrintWriter output-stream)
           existingformat (.getOntologyFormat owl-ontology-manager
                                              (get-current-jontology))
           this-format
           (cond
            (= format :omn) (ManchesterOWLSyntaxOntologyFormat.)
            (= format :owl) (OWLXMLOntologyFormat.)
            :else format)]
       (when (.isPrefixOWLOntologyFormat this-format)
         (dorun
          (map #(.setPrefix this-format (:prefix %)
                            (str (.toString (:iri %)) "#"))
               (vals @ontology-for-namespace))))
       (.print file-writer prepend)
       (.flush file-writer)
       (.setPrefix this-format (get-current-prefix)
                   (str (.toString (get-current-iri)) "#"))
       (.saveOntology owl-ontology-manager (get-current-jontology)
                      this-format output-stream))))

(defn- iriforname [name]
  (IRI/create (str (get-current-iri) "#" name)))

(defn- get-create-object-property [name]
  (.getOWLObjectProperty ontology-data-factory
                         (iriforname name)))

(defn- ensure-object-property [prop]
  (cond
   (instance? owl.owl.AxiomedEntity prop)
   (ensure-object-property (:entity prop))
   (instance? org.semanticweb.owlapi.model.OWLObjectProperty prop)
   prop
   (string? prop)
   (get-create-object-property prop)
   true
   (throw (IllegalArgumentException.
           (str "Expecting an object property. Got: " prop)))))

(defn- get-create-class [name]
  (.getOWLClass ontology-data-factory
                (iriforname name)))

(defn- ensure-class [clz]
  "If clz is a String return a class of with that name,
else if clz is a OWLClassExpression add that."
  (cond
   (instance? owl.owl.AxiomedEntity clz)
   (ensure-class (:entity clz))
   (instance? org.semanticweb.owlapi.model.OWLClassExpression clz)
   clz
   (string? clz)
   (get-create-class clz)
   true
   (throw (IllegalArgumentException.
           (str "Expecting a class. Got: " clz)))))

(defn- add-axiom [axiom]
  (.applyChange owl-ontology-manager
                (AddAxiom. (get-current-jontology) axiom))
  axiom)

(defn- remove-axiom [axiom]
  (.applyChange owl-ontology-manager
                (RemoveAxiom. (get-current-jontology) axiom))
  axiom)

(defn remove-entity
  "Remove from the ontology an entity created and added by
owlclass, defclass, objectproperty or defoproperty. Entity is the value
returned by these functions. 

This removes all the axioms that were added. So, for example, a form such as

   (defclass a
      :subclass b
      :equivalent c)

adds three axioms -- it declares a, makes it a subclass of b, and equivalent
of c."
  [entity]
  (dorun
   (map #(remove-axiom %)
        (:axioms entity))))

(defn- add-one-frame
  "Adds a single frame to the ontology.

OWL isn't actually frame based, even if Manchester syntax is. My original
intention is that this would be suitable for adding frame in to the ontology
but in practice this doesn't work, as not everything is an axiom. 
"
  [frame-adder name frame]
  (let [clazz (ensure-class name)
        axiom (frame-adder clazz frame)]
    (add-axiom axiom)
    axiom))
  
(defn- add-frame
"Adds frames with multiple objects to the ontology"
  [frame-adder name frame]
  (doall
   (map (fn[x]
          (add-one-frame frame-adder name x))
        ;; owlsome, only, someonly return lists
        (flatten frame))))

(defn- create-subclass-axiom
  "Creates a subclass axiom for the given class and subclass.

The class needs to be a OWLClass object, while the subclass can be a string,
class, or class expression. "
  [clazz subclass]
  (.getOWLSubClassOfAxiom
   ontology-data-factory
   clazz
   (ensure-class subclass)))

(defn add-subclass
"Adds a specific class to the ontology"
  ([name subclass]
     (add-frame create-subclass-axiom
                name
                subclass)))

(defn- create-equivalent-axiom [clazz equivalent]
  (.getOWLEquivalentClassesAxiom
   ontology-data-factory
   clazz
   (ensure-class equivalent)))

(defn add-equivalent
  ([name equivalent]
     (add-frame create-equivalent-axiom name equivalent)))

(defn- create-class-axiom [clazz _]
  (.getOWLDeclarationAxiom
   ontology-data-factory
   clazz))

(defn add-disjoint-union [clazz subclasses]
  (let [ensured-subclasses
        (doall (map #(ensure-class %) subclasses))
        ]
    (list
     (add-axiom
      (.getOWLDisjointUnionAxiom
       ontology-data-factory
       (ensure-class clazz)
       (java.util.HashSet.  ensured-subclasses))))))

(defn add-class[name]
  (add-one-frame create-class-axiom name ""))

(defn add-domain [property domainlist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [domain]
        (add-axiom
         (.getOWLObjectPropertyDomainAxiom
          ontology-data-factory property
          (ensure-class domain))))
      domainlist))))

(defn add-range [property rangelist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [range]
        (add-axiom
         (.getOWLObjectPropertyRangeAxiom
          ontology-data-factory property
          (ensure-class range))))
      rangelist))))

(defn add-inverse [property inverselist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [inverse]
        (add-axiom
         (.getOWLInverseObjectPropertiesAxiom
          ontology-data-factory property
          (ensure-object-property inverse))))
      inverselist))))


(defn add-superproperty [property superpropertylist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [superproperty]
        (add-axiom
         (.getOWLSubObjectPropertyOfAxiom
          ontology-data-factory property
          (ensure-object-property superproperty))))
      superpropertylist))))


;; Really it would make more sense to use keywords, but this breaks the
;; groupify function which expects alternative keyword value args. The
;; approach of using strings and symbol names here is scary -- if someone does
;; (defclass transitive) for example, it's all going to break. I don't think
;; that the const does what might be though. 
(def ^:const transitive "transitive")
(def ^:const functional "functional")
(def ^:const inversefunctional "inversefunctional")

(def
  ^{:private true}
  charfuncs
  {transitive #(.getOWLTransitiveObjectPropertyAxiom %1 %2)
   functional #(.getOWLFunctionalObjectPropertyAxiom %1 %2)
   inversefunctional #(.getOWLInverseFunctionalObjectPropertyAxiom %1 %2)
   })

(defn add-characteristics [property characteristics]
  (doall
   (map
    (fn [x]
      (when-not (get charfuncs x)
        (throw (IllegalArgumentException. "Characteristic is not recognised:" x)))
      (add-axiom
       ((get charfuncs x)
        ontology-data-factory (ensure-object-property property))))
    characteristics)))

(def
  ^{:dynamic true}
  *default-frames* nil)

(def
  ^{:doc "Axioms we have added recently"
    :dynamic true}
  recent-axiom-list
  nil)

;; object properties
(defn objectproperty-explicit
  [name {:keys [domain range inverseof subpropertyof characteristics] :as all}]
  (let [property (get-create-object-property name)
        axioms
        (concat 
         (list (add-axiom
                (.getOWLDeclarationAxiom
                 ontology-data-factory property)))
         (add-domain property domain)
         (add-range property range)
         (add-inverse property inverseof)
         (add-superproperty property subpropertyof)
         (add-characteristics property characteristics)
         )]
    ;; store classes if we are in an inverse binding
    (when (seq? recent-axiom-list)
      (set! recent-axiom-list
            (concat (list property) recent-axiom-list)))
    (AxiomedEntity. property axioms)))


(defn objectproperty
  [name & frames]
  (objectproperty-explicit
   name
   (util/check-keys
    (merge-with concat
                (util/hashify frames)
                *default-frames*)
    [:domain :range :inverseof :subpropertyof :characteristics])))

(defmacro defoproperty [property & frames]
  `(let [property-name# (name '~property)
         property# (owl.owl/objectproperty property-name# ~@frames)]
     (def ~property property#)
     property#))

;; restrictions! name clash -- we can do nothing about this, so accept the
;; inconsistency and bung owl on the front.
(defn owlsome [property & classes]
  (doall
   (map #(.getOWLObjectSomeValuesFrom
          ontology-data-factory
          (ensure-object-property property)
          (ensure-class %))
        classes)))

(defn only [property & classes]
  (doall
   (map #(.getOWLObjectAllValuesFrom
          ontology-data-factory
          (ensure-object-property property)
          (ensure-class %))
        classes)))

;; forward declaration
(declare owlor)
(defn someonly [property & classes]
  (concat
   (apply owlsome (concat (list property) classes))
   (only property (apply owlor classes))))


;; union, intersection
(defn owland [& classes]
  (.getOWLObjectIntersectionOf
   ontology-data-factory
   (java.util.HashSet. 
    (doall (map
            #(ensure-class %)
            ;; flatten list for things like owlsome which return lists
            (flatten classes))))))

;; short cuts for the terminally lazy. Still prefix!
(def && owland)

(defn owlor [& classes]
  (.getOWLObjectUnionOf
   ontology-data-factory
   (java.util.HashSet.
    (doall (map #(ensure-class %)
                (flatten classes))))))

(def || owlor)

(defn owlnot [& class]
  (.getOWLObjectComplementOf
   ontology-data-factory
   (ensure-class class)))

(def ! owlnot)

;; cardinality
(defn atleast [cardinality property class]
  (.getOWLObjectMinCardinality
   ontology-data-factory cardinality
   (ensure-object-property property)
   (ensure-class class)))

(defn atmost [cardinality property class]
  (.getOWLObjectMaxCardinality
   ontology-data-factory cardinality
   (ensure-object-property property)
   (ensure-class class)))

(defn exactly [cardinality property class]
  (.getOWLObjectExactCardinality
   ontology-data-factory cardinality
   (ensure-object-property property)
   (ensure-class class)))

(declare ensure-individual)
(defn
  oneof [& individuals]
  (.getOWLObjectOneOf
   ontology-data-factory
   (java.util.HashSet.
    (doall
     (map #(ensure-individual %)
          (flatten individuals))))))
  



;; annotations
(defn add-annotation
  [name annotation-list]
  (doall
   (map
    (fn[annotation]
      (let [axiom
            (.getOWLAnnotationAssertionAxiom
             ontology-data-factory
             (.getIRI (get-create-class name)) annotation)]
        (add-axiom axiom)))
    annotation-list)))

(defn annotation
  ([annotation-property literal]
     (annotation annotation-property literal "en"))
  ([annotation-property literal language]
     (.getOWLAnnotation
      ontology-data-factory
      annotation-property 
      (.getOWLLiteral ontology-data-factory literal language))))

;; various annotation types
(def label
  (partial annotation (.getRDFSLabel ontology-data-factory)))

(def owlcomment
  (partial annotation (.getRDFSComment ontology-data-factory)))

(def isdefinedby
  (partial annotation (.getRDFSIsDefinedBy ontology-data-factory)))

(def seealso
  (partial annotation (.getRDFSSeeAlso ontology-data-factory)))


(defn owlclass-explicit
  ([name frames]
     (let [classname (or (first (:name frames)) name)
           class
           (ensure-class classname)]
       ;; store classes if we are in a disjoint binding
       (when (seq? recent-axiom-list)
         (set! recent-axiom-list
               (concat (list class)
                       recent-axiom-list)))
       ;; generate an axiomed entity
       (AxiomedEntity.
        class 
        (concat
         (list
          ;; add-class returns a single axiom -- concat balks at this
          (add-class classname))
         (add-subclass classname (:subclass frames))
         (add-equivalent classname (:equivalent frames))
         (add-annotation classname (:annotation frames))
         ;; change these to add to the annotation frame instead perhaps?
         (when (:comment frames)
           (add-annotation classname
                           (list (owlcomment
                                  (first (:comment frames))))))

         (when (:label frames)
           (add-annotation classname
                           (list (label
                                  (first
                                   (:label frames))))))))))
  ([name]
     (owlclass-explicit name {})))


(defn owlclass
  ([name & frames]
     (owlclass-explicit
      name
      (util/check-keys
       (merge-with
               concat
               (util/hashify frames)
               *default-frames*)
       [:subclass :equivalent :annotation :name :comment :label]))))

(defmacro defclass [classname & frames]
  `(let [string-name# (name '~classname)
         class# (owl.owl/owlclass string-name# ~@frames)]
     (def ~classname class#)
     class#))


(defn disjointclasseslist [list]
  (let [classlist
        (doall
         (map
          (fn [x]
            (ensure-class x))
          list))]
    (add-axiom 
     (.getOWLDisjointClassesAxiom
      ontology-data-factory
      (into-array OWLClassExpression
                  classlist)))))
  
(defn disjointclasses [& list]
  (disjointclasseslist list))

(defn- get-create-individual [individual]
  (.getOWLNamedIndividual ontology-data-factory
                          (iriforname individual)))

(defn- ensure-individual [individual]
  (cond (instance? org.semanticweb.owlapi.model.OWLIndividual)
        individual
        (string? individual)
        (get-create-individual individual)
        true
        (throw (IllegalArgumentException.
                (str "Expecting an Inidividual. Got: " individual)))))

;; need to support all the different frames here...
;; need to use hashify
(defn individual-add-types [name types]
  (let [individual (ensure-individual name)]
    (doall
     (map
      (fn [type]
        (add-axiom
         (.getOWLClassAssertionAxiom
          ontology-data-factory
          (ensure-class type)
          individual)))
      types))
    individual))

(defn individual [name & frames]
  (let [hframes
        (util/check-keys 
         (util/hashify frames)
         [:types])]
    (individual-add-types name (:types hframes))))



;; owl imports

(defn addimport [ontology]
  (.applyChange owl-ontology-manager
                (AddImport. (get-current-jontology)
                            (.getOWLImportsDeclaration
                             ontology-data-factory
                             (IRI/create (:iri ontology))))))


;; return type of individual is buggered
(defmacro defindividual [individualname & frames]
  `(let [string-name# (name '~individualname)
         individual# (owl.owl/individual string-name# ~@frames)]
     (def ~individualname individual#)
     individual#))

;; convienience macros
(defmacro as-disjoint [& body]
  `(do ;; delete all recent classes
     (binding [owl.owl/recent-axiom-list '()]
       ;; do the body
       ~@body
       ;; set them disjoint if there is more than one. if there is only one
       ;; then it would be illegal OWL2. this macro then just shields the body
       ;; from any other as-disjoint statements.
       (println "recent axiom list" owl.owl/recent-axiom-list)
       (println "recent axiom list" (count owl.owl/recent-axiom-list))
       (when (< 1 (count owl.owl/recent-axiom-list))
         (println "Adding disjoints")
         (owl.owl/disjointclasseslist
          owl.owl/recent-axiom-list)))))

(defmacro as-inverse [& body]
  `(do
     (binding [owl.owl/recent-axiom-list '()]
       ~@body
       (when-not (= (count owl.owl/recent-axiom-list) 2)
         (throw (IllegalArgumentException. "Can only have two properties in as-inverse")))
       (owl.owl/add-inverse
        (first owl.owl/recent-axiom-list)
        (rest owl.owl/recent-axiom-list))
       )))






;; bind to 
(defmacro with-ontology [ontology & body]
  `(binding [owl.owl/*current-bound-ontology* ~ontology]
     ~@body))


;; specify default frames which should be merged with additional frames passed
;; in. place into a dynamic variable and then use (merge-with concat) to do
;; the business
(defmacro with-default-frames [frames & body]
  `(binding [owl.owl/*default-frames*
             (owl.util/hashify ~frames)]
     ~@body))


(defmacro as-disjoint-subclasses [superclass & body]
  ;; crap out badly if superclass isn't a class object
  `(with-default-frames [:subclass ~superclass]
     (as-disjoint
      ~@body)))


(defmacro declare-classes
  "Declares all the classes given in names.

This is mostly useful for forward declarations, but the classes declared will
have any default frames or disjoints if `as-disjoints' or
`with-default-frames' or equivalent macros are in use.

See `defclassn' to define many classes with frames.
"
  [& names]
  `(do ~@(map
          (fn [x#]
            `(defclass ~x#))
          names)))

(defmacro defclassn
  "Defines many classes at once.

Each class and associated frames should be supplied as a vector.

See `declare-classes' where frames (or just default frames) are not needed.
"
  [& classes]
  `(do ~@(map
          (fn [x#]
            `(defclass ~@x#)) classes)))

;; predicates
(defn- recurseclass?
  "Determine class relationship

Returns true if targetclass is directly or indirectly related to a class in
namelist where recursefunction returns all direct relationships"
  [namelist targetclass recursefunction]
  (and (first namelist)
       (or (= (first namelist)
              targetclass)
           (recurseclass? (recursefunction
                           (first namelist))
                          targetclass
                          recursefunction)
           (recurseclass? (rest namelist)
                          targetclass
                          recursefunction
                          ))))

(defn isuperclasses
  "Returns the direct superclasses of name.
Name can be either a class or a string name. Returns a list of classes"
  [name]
  (.getSuperClasses (ensure-class name)
                    (get-current-jontology)))


(defn superclass?
  "Returns true is name has superclass as a superclass"
  [name superclass]
  (recurseclass? (list (ensure-class name))
                 (ensure-class superclass)
                 isuperclasses))

(defn isubclasses
  "Returns the direct subclasses of name."
  [name]
  (.getSubClasses (ensure-class name)
                  (get-current-jontology)))

(defn subclass?
  "Returns true if name has subclass as a subclass"
  [name subclass]
  (recurseclass? (list (ensure-class name))
               (ensure-class subclass)
               isubclasses))

(defn- subclasses-1 [classlist]
  ;; if there are no subclasses return empty list
  (if (= 0 (count classlist))
    (list)
    (concat (list (first classlist))
            ;; can't use recur, not in tail position
            (subclasses-1 (rest classlist))
            (subclasses-1 (isubclasses (first classlist))))))

(defn subclasses [class]
  (subclasses-1 (isubclasses class)))

