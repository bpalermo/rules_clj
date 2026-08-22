(ns conformance.protocols
  "A protocol and a record implementing it, compiled in their own target.

  Protocols are the sharp edge of ahead-of-time compilation: defprotocol generates a
  Java interface, and the JVM considers two classes of the same name loaded by
  different classloaders to be different types. If a build lets this interface be
  compiled into more than one jar, `satisfies?` starts answering false about values
  that plainly do satisfy it.")

(defprotocol Greeter
  (greet [this who] "Greets who."))

(defrecord Formal []
  Greeter
  (greet [_ who] (str "Good day, " who)))
