(ns store.cart
  (:require [store.item :as item]))

(defn add
  [cart name]
  (conj (vec cart) (item/normalise name)))

(defn total
  [cart]
  (count cart))
