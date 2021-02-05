(ns metabase.api.notify-test
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.test :refer :all]
            [environ.core :as env]
            [metabase.http-client :as http]
            [metabase.models.database :as database]
            [metabase.server.middleware.auth :as auth]
            [metabase.server.middleware.util :as middleware.u]
            [metabase.test :as mt]
            [metabase.test.fixtures :as fixtures]
            [metabase.util :as u]))

(use-fixtures :once (fixtures/initialize :db :web-server))

(defn- do-with-api-key [api-key thunk]
  (with-redefs [env/env (assoc env/env :mb-api-key api-key)]
    (thunk)))

(defmacro ^:private with-api-key [api-key & body]
  `(do-with-api-key ~api-key (fn [] ~@body)))

(deftest unauthenticated-test
  (with-api-key "testing-api-key"
    (testing "POST /api/notify/db/:id"
      (testing "endpoint should require authentication"
        (is (= (get middleware.u/response-forbidden :body)
               (http/client :post 403 "notify/db/100")))))))

(deftest not-found-test
  (with-api-key "testing-api-key"
    (testing "POST /api/notify/db/:id"
      (testing "database must exist or we get a 404"
        (is (= {:status 404
                :body   "Not found."}
               (try (client/post (http/build-url (format "notify/db/%d" Integer/MAX_VALUE) {})
                                 {:accept  :json
                                  :headers {"X-METABASE-APIKEY" "testing-api-key"
                                            "Content-Type"      "application/json"}})
                    (catch clojure.lang.ExceptionInfo e
                      (select-keys (:object (ex-data e)) [:status :body])))))))))

(deftest post-db-id-test
  (with-api-key "testing-api-key"
    (mt/test-drivers (mt/normal-drivers)
      (let [table-name (->> (mt/db) database/tables first :name)
            post       (fn post-api
                         ([payload] (post-api payload 200))
                         ([payload expected-code]
                          (mt/client :post expected-code (format "notify/db/%d" (u/the-id (mt/db)))
                                     {:request-options
                                      {:headers {"X-METABASE-APIKEY" "testing-api-key"
                                                 "Content-Type"      "application/json"}}}
                                     payload)))]
        (testing "sync just table when table is provided"
          (let [long-sync-called? (atom false), short-sync-called? (atom false)]
            (with-redefs [metabase.sync/sync-table!                        (fn [_table] (reset! long-sync-called? true))
                          metabase.sync.sync-metadata/sync-table-metadata! (fn [_table] (reset! short-sync-called? true))]
              (post {:scan :full, :table_name table-name})
              (is @long-sync-called?)
              (is (not @short-sync-called?)))))
        (testing "only a quick sync when quick parameter is provided"
          (let [long-sync-called? (atom false), short-sync-called? (atom false)]
            (with-redefs [metabase.sync/sync-table!                        (fn [_table] (reset! long-sync-called? true))
                          metabase.sync.sync-metadata/sync-table-metadata! (fn [_table] (reset! short-sync-called? true))]
              (post {:scan :schema, :table_name table-name})
              (is (not @long-sync-called?))
              (is @short-sync-called?))))
        (testing "full db sync by default"
          (let [full-sync? (atom false)]
            (with-redefs [metabase.sync/sync-database! (fn [_db] (reset! full-sync? true))]
              (post {})
              (is @full-sync?))))
        (testing "simple sync with params"
          (let [full-sync?   (atom false)
                smaller-sync (atom true)]
            (with-redefs [metabase.sync/sync-database!                  (fn [_db] (reset! full-sync? true))
                          metabase.sync.sync-metadata/sync-db-metadata! (fn [_db] (reset! smaller-sync true))]
              (post {:scan :schema})
              (is (not @full-sync?))
              (is @smaller-sync))))
        (testing "errors on unrecognized scan options"
          (is (= "Optional scan parameter must be either \"full\" or \"scan\""
                 (post {:scan :unrecognized} 400))))))))
