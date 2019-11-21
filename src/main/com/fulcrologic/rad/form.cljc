(ns com.fulcrologic.rad.form
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.controller :as controller]
    #?(:clj [com.fulcrologic.rad.database-adapters.db-adapter :as dba])
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.rendering.data-field :refer [render-field]]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]))

#?(:clj
   (pc/defmutation save-form [env params]
     {::pc/params #{::diff ::delta}}
     (dba/save-form env params))
   :cljs
   (m/defmutation save-form [params]
     (action [env] :noop)))
;; TODO: Support for a generalized focus mechanism to show the first field that has a problem

;; TODO: Allow form to override validation on a field, with fallback to what is declared on the attribute

(defn config [env] (uism/retrieve env :config))

(defn attr-value
  "When interpreting an event from a form field, this function will extract the pair of:
  [attribute value] from the `env`."
  [env]
  [(-> env ::uism/event-data ::attr/qualified-key)
   (-> env ::uism/event-data :value)])

(defn set-attribute*
  "Mutation helper: Set the given attribute's value in app state."
  [state-map form attribute value])

(defn render-form [this props]
  (let [{::attr/keys [attributes]} (comp/component-options this)]
    (mapv
      (fn [k]
        (log/spy :info k)
        (render-field this k props))
      attributes)))

(defn- start-edit! [app TargetClass {machine-id ::id
                                     ::rad/keys [id target-route] :as options}]
  (when-not machine-id
    (log/error "Missing form machine id route on start-edit!"))
  (when-not target-route
    (log/error "Missing target route on start-edit!"))
  (when-not id
    (log/error "Missing ID on start-edit!"))
  (log/debug "START EDIT" (comp/component-name TargetClass))
  (let [id-key (some-> TargetClass (comp/ident {}) first)
        ;; TODO: Coercion from string IDs to type of ID field
        id     (new-uuid id)]
    (df/load! app [id-key id] TargetClass
      {:post-action (fn [{:keys [state]}]
                      (log/debug "Marking the form complete")
                      (fns/swap!-> state
                        (assoc-in [id-key id :ui/new?] false)
                        (fs/mark-complete* [id-key id]))
                      (controller/io-complete! app options))})))

;; TODO: ID generation pluggable? Use tempids?  NOTE: The controller has to generate the ID because the incoming
;; route is already determined
(defn- start-create! [app TargetClass {machine-id ::id
                                       ::rad/keys [target-route tempid]}]
  (when-not machine-id
    (log/error "Controller failed to pass machine id"))
  (when-not tempid
    (log/error "Creating an entity, but initial ID missing"))
  (log/debug "START CREATE" (comp/component-name TargetClass))
  (let [id-key         (some-> TargetClass (comp/ident {}) first)
        ident          [id-key tempid]
        fields         (map attr/key->attribute (comp/component-options TargetClass ::attr/attributes))
        default-values (comp/component-options TargetClass ::default-values)
        ;; TODO: Make sure there is one and only one unique identity key on the form
        initial-value  (into {:ui/new? true}
                         (keep (fn [{::keys      [default-value]
                                     ::attr/keys [qualified-key unique]}]
                                 ;; NOTE: default value can come from attribute or be set/overridden on form itself
                                 (let [default-value (or (get default-values qualified-key) default-value)]
                                   (cond
                                     (= unique :identity) [qualified-key tempid]
                                     default-value [qualified-key default-value]))))
                         fields)
        filled-fields  (keys initial-value)
        tx             (into []
                         (map (fn [k]
                                (fs/mark-complete! {:entity-ident ident
                                                    :field        k})))
                         filled-fields)]
    ;; NOTE: pre-merge of form does add of form config...this is probably not right. Should probably trigger a self
    ;; event for that
    (merge/merge-component! app TargetClass initial-value)
    (when (seq tx)
      (log/debug "Marking fields with default values complete")
      (comp/transact! app tx))
    (controller/io-complete! app {::controller/id    machine-id
                                  ::rad/target-route target-route})))

(defn confirm-exit? [env]
  (boolean (some-> env (uism/actor-class :actor/form) comp/component-options ::confirm-exit?)))

(defn exit-form [{::uism/keys [fulcro-app] :as env}]
  (let [Form         (uism/actor-class env :actor/form)
        id           (uism/retrieve env ::controller/id)
        cancel-route (some-> Form comp/component-options ::cancel-route)]
    (when-not cancel-route
      (log/error "Don't know where to route on cancel. Add ::form/cancel-route to your form."))
    ;; TODO: probably return to original route instead
    (controller/route-to! fulcro-app id (or cancel-route []))
    (uism/exit env)))

(defn ask-before-leaving [{::uism/keys [fulcro-app] :as env}]
  (if (confirm-exit? env)
    (uism/activate env :state/asking-to-discard-changes)
    (exit-form env)))

(>defn calc-diff
  [env]
  [::uism/env => (s/keys :req [::diff ::delta])]
  (let [{::uism/keys [state-map event-data]} env
        form-ident (uism/actor->ident env :actor/form)
        Form       (uism/actor-class env :actor/form)
        props      (fns/ui->props state-map Form form-ident)
        new?       (uism/alias-value env :new?)
        delta      (fs/dirty-fields props true {:new-entity? new?})
        diff       (fs/dirty-fields props false {:new-entity? new?})]
    {::delta delta
     ::diff  diff}))

(def global-events
  {:event/will-leave     {::uism/handler (fn [env]
                                           ;; TODO: Handle the controller asking if it is OK to abort this edit
                                           env)}
   ;; TODO: hook this up in controller
   :event/form-abandoned {::uism/handler (fn [env]
                                           (uism/exit env))}})

(defstatemachine form-machine
  {::uism/actors
   #{:actor/form}

   ::uism/aliases
   {:new?                 [:actor/form :ui/new?]
    :confirmation-message [:actor/form :ui/confirmation-message]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [fulcro-app event-data]} env
                            {::controller/keys [id]
                             ::keys            [action]} event-data
                            Form (uism/actor-class env :actor/form)]
                        (when-not id
                          (log/error "Controller ID not sent to form SM."))
                        (when-not (#{:create :edit} action)
                          (log/error "Unexpected action" action))
                        (if (= :create action)
                          (start-create! fulcro-app Form event-data)
                          (start-edit! fulcro-app Form event-data))
                        (-> env
                          (uism/store ::action action)
                          (uism/store ::controller/id id)
                          (uism/activate :state/editing))))}

    :state/asking-to-discard-changes
    {::uism/events
     {:event/ok     {::uism/handler exit-form}
      :event/cancel {::uism/handler (fn [env] (uism/activate env :state/editing))}}}

    :state/saving
    (merge global-events
      {::uism/events
       {:event/save-failed {::uism/handler (fn [env]
                                             ;; TODO: Handle failures
                                             env)}
        :event/saved       {::uism/handler (fn [env]
                                             (let [form-ident (uism/actor->ident env :actor/form)]
                                               (-> env
                                                 (uism/apply-action fs/entity->pristine* form-ident))))}}})

    :state/editing
    (merge global-events
      {::uism/events
       {:event/attribute-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   ;; NOTE: value at this layer is ALWAYS typed to the attribute.
                                                   ;; The rendering layer is responsible for converting the value to/from
                                                   ;; the representation needed by the UI component (e.g. string)
                                                   (let [{:keys       [value]
                                                          ::attr/keys [qualified-key]} event-data
                                                         form-ident     (uism/actor->ident env :actor/form)
                                                         path           (when (and form-ident qualified-key)
                                                                          (conj form-ident qualified-key))
                                                         ;; TODO: Decide when to properly set the field to marked
                                                         mark-complete? true]
                                                     (when-not path
                                                       (log/error "Unable to record attribute change. Path cannot be calculated."))
                                                     (cond-> env
                                                       mark-complete? (uism/apply-action fs/mark-complete* form-ident qualified-key)
                                                       ;; FIXME: Data coercion needs to happen at UI and db layer, but must
                                                       ;; be extensible. You should be able to select a variant of a form
                                                       ;; control for a given db-supported type. This allows the types
                                                       ;; to be fully extensible since the db adapter can isolate that
                                                       ;; coercion, and the UI control variant can do coercion at the UI
                                                       ;; layer.
                                                       ;; FIXME: One catch with coercion: sometimes the value has transient
                                                       ;; values during input that will not properly coerce. This means UI
                                                       ;; controls will need to buffer the user-interaction value and only
                                                       ;; do the commit/coercion at the end.
                                                       path (uism/apply-action assoc-in path value))))}
        :event/blur              {::uism/handler (fn [env] env)}
        :event/save              {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (let [form-class   (uism/actor-class env :actor/form)
                                                         data-to-save (calc-diff env)
                                                         params       (merge event-data data-to-save)]
                                                     (-> env
                                                       (uism/trigger-remote-mutation :actor/form `save-form
                                                         {::uism/error-event :event/save-failed
                                                          :params            params
                                                          ;; TODO: Make return optional?
                                                          ::m/returning      form-class
                                                          ::uism/ok-event    :event/saved}))))}
        :event/reset             {::uism/handler (fn [env]
                                                   (let [form-ident (uism/actor->ident env :actor/form)]
                                                     (uism/apply-action env fs/pristine->entity* form-ident)))}
        :event/cancel            {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}])}}})}})

(defmethod controller/-start-io! ::rad/form
  [{::uism/keys [fulcro-app] :as env} TargetClass {::controller/keys [id]
                                                   ::rad/keys        [target-route] :as options}]
  (log/info "Starting I/O processing for RAD Form" (comp/component-name TargetClass))
  (let [[_ action id] target-route
        target-id       (new-uuid id)
        form-machine-id [(first (comp/ident TargetClass {})) target-id]
        event-data      (assoc options
                          ::id form-machine-id
                          ::rad/id target-id
                          ::rad/tempid target-id
                          ::action (some-> action str keyword))]
    (uism/begin! fulcro-app form-machine form-machine-id
      {:actor/form (uism/with-actor-class form-machine-id TargetClass)}
      event-data)
    (if (= action "create")
      (start-create! fulcro-app TargetClass event-data)
      (start-edit! fulcro-app TargetClass event-data))
    (uism/activate env :state/routing)))
