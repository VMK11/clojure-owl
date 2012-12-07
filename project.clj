(defproject uk.org.russet/clojure-owl "0.3"
  :description "Clojure-owl enables building OWL ontologies in a programmatic environment."
  :url "http://www.russet.org.uk/blog/clojure-owl"
  :plugins [[lein-swank "1.4.4"]]
  :repositories [["maven" "http://repo1.maven.org/maven2"]

                 ;; hermit comes from here
                 ["phillord" "http://homepages.cs.ncl.ac.uk/phillip.lord/maven"]
                 ]

  :resource-paths ["src/resources"]
  
  :scm {:url "https://phil.lord.bio@code.google.com/p/clojure-owl/"
        :name "hg"} 
  
  :license {:name "LGPL"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :dependencies [
                 ;; owl API
                 [net.sourceforge.owlapi/owlapi-api "3.4.2"]
                 [net.sourceforge.owlapi/owlapi-apibinding "3.4.2"]
                 [net.sourceforge.owlapi/owlapi-distribution "3.4.2"]

                 ;; clojure
                 [org.clojure/clojure "1.4.0"]
                 
                 ;; reasoners
                 [org.semanticweb.elk/elk-owlapi "0.3.1"]
                 [org.semanticweb.hermit/HermiT "1.3.6.1"]
                 

                 ]





)