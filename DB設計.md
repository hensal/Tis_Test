# VRView DB設計

## 1. 目的
- 本書は、VRViewで利用するデータベースのテーブル設計を定義する。
- API設計と整合する永続化項目、主な関連、運用上の注意を記載する。

## 2. 設計方針
- API起点で必要なデータのみを保持する。
- 認証基盤（ユーザー）は Keycloak 管理とし、アプリDBではKeycloak連携に必要なロール関連情報のみ保持する。
- Keycloak管理テーブル（keycloak_role_master, user_roles）は本DBの作成対象外とする。
- 監査系日時は API の日時形式要件に合わせて日時型で統一する。
- 論理削除を基本とし、通常検索では削除済みを除外する。
- 施設を集約ルートとし、画像、地図、地図ポイント、移動ポイント、権限、付箋、設備を関連として管理する。
- 施設関連情報は facilities_tree を集約ルートとして保持する。

## 3. 機能とテーブル対応
| 機能 | 主テーブル | 補助テーブル | 備考 |
|------|------------|--------------|------|
| facilities | facilities_tree | facility_images, maps, map_points, hotspots, facility_role_permission | /vr-view の表示元を含む |
| annotations | annotations | annotation_comments, annotation_files | comments は単独APIでも取得 |
| annotation_comments | annotation_comments | - | コメント更新・削除APIは未定義 |
| equipment | equipments | equipment_masters, equipment_types | 一覧検索で種類・名称結合 |
| equipment_masters | equipment_types, equipment_masters | - | bulk保存に対応 |
| files | files | - | ファイル本体はストレージ、メタ情報を保持 |
| me/keycloak | （作成対象外）keycloak_role_master, user_roles | permission_master, facility_role_permission | Keycloak連携用ロール権限情報を保持 |

## 4. テーブル設計

### 4.1 files
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| ファイルID | file_id | BIGINT | Y | Y | 自動採番 |
| ファイル名 | file_name | VARCHAR(128) |  | Y | パスを含まない |
| 保存パス | file_path | VARCHAR(256) |  |  | ファイル名を含まない。現行は /{file_id} を採用（将来変更に備えて保持） |
| ファイル種別 | file_type | VARCHAR(32) |  | Y | 用途種別（annotation / vr_image / map_image） |
| ファイルサイズ | file_size | BIGINT |  | Y | Byte |
| S3バケット | s3_bucket | VARCHAR(512) |  | Y | 保存先S3バケットURL |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.2 facilities_tree
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 施設ID | facility_id | BIGINT | Y | Y | 自動採番 |
| 親施設ID | parent_id | BIGINT |  |  | ルートは NULL |
| 施設名称 | facility_name | VARCHAR(100) |  | Y |  |
| 表示順序 | sort_order | BIGINT |  | Y | 同一親配下の手動並び替え用。10,000,000 間隔（例: 10,000,000 / 20,000,000 / 30,000,000）で採番 |
| 階層レベル | tree_level | INT |  |  | 参照高速化用キャッシュ |
| 末端フラグ | is_leaf | BOOLEAN |  | Y |  |
| 公開モード | public_flag | CHAR(2) |  | Y | 01/NULL:非公開, 02:この施設のみ公開, 03:下位施設含め公開 |
| 屋外フラグ | outer_flag | BOOLEAN |  | Y | True:屋外, False/NULL:屋内 |
| 施設詳細 | facility_description | VARCHAR(500) |  |  |  |
| 権限設定フラグ | authority_setting_flg | BOOLEAN |  | Y | True:個別権限, False/NULL:親権限継承 |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

同一親配下の並び替えは `sort_order` の昇順で表示する。`sort_order` は BIGINT とし、10,000,000 間隔で採番する。更新時は必ずトランザクションで同一親配下の `sort_order` を再採番し、重複しないようにする。隙間が詰まった場合は、その親配下だけ再採番すればよい。

### 4.3 facility_images
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 画像ID | image_id | BIGINT | Y | Y | 自動採番 |
| 施設ID | facility_id | BIGINT |  | Y | FK -> facilities_tree |
| ファイルID | file_id | BIGINT |  | Y | FK -> files |
| 画像名 | image_name | VARCHAR(256) |  |  |  |
| 撮影高さ(mm) | shooting_height | INT |  | Y | 床面からカメラ中心までの高さ（mm） |
| 天井高(mm) | ceiling_height | INT |  | Y | 床面から天井までの高さ（mm） |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.4 maps
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 地図ID | map_id | BIGINT | Y | Y | 自動採番 |
| 施設ID | facility_id | BIGINT |  | Y | FK -> facilities_tree |
| ファイルID | file_id | BIGINT |  | Y | FK -> files |
| 地図名 | map_name | VARCHAR(256) |  | Y |  |
| 画像幅 | image_width | INT |  | Y |  |
| 画像高さ | image_height | INT |  | Y |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.5 map_points
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 地図ポイントID | map_point_id | BIGINT | Y | Y | 自動採番 |
| 地図ID | map_id | BIGINT |  | Y | FK -> maps |
| 施設ID | facility_id | BIGINT |  | Y | ポイント対象施設 |
| 座標X | x | INT |  | Y |  |
| 座標Y | y | INT |  | Y |  |
| 遷移先ID | target_id | BIGINT |  | Y |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.6 keycloak_role_master（作成対象外）
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| ロールID | keycloak_role_id | BIGINT | Y | Y |  |
| ロール名 | keycloak_role_name | VARCHAR(32) |  | Y |  |
| 権限リスト | permissions | TEXT |  | Y | カンマ区切りで複数保持 |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.6.1 user_roles（作成対象外）
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| ユーザーロールID | keycloak_user_role_id | BIGINT | Y | Y | 自動採番 |
| KeycloakロールID | keycloak_role_id | BIGINT |  | Y | 参照: Keycloak側 keycloak_role_master（本DB外） |
| KeycloakユーザーID | keycloak_user_id | VARCHAR(64) |  | Y | Keycloak user id |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |


### 4.7 permission_master
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 権限ID | permission_id | BIGINT | Y | Y | 自動採番 |
| 権限名称 | permission_name | VARCHAR(32) |  | Y | 例: view, annotation_view |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.8 facility_role_permission
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 施設権限ロールID | facility_role_permission_id | BIGINT | Y | Y | 自動採番 |
| 権限ID | permission_id | BIGINT |  | Y | FK -> permission_master |
| 施設ID | facility_id | BIGINT |  | Y | FK -> facilities_tree |
| KeycloakロールID | keycloak_role_id | BIGINT |  | Y | Keycloak側ロールID（本DBでFKは持たない） |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.9 equipment_types
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 設備種類ID | equipment_type_id | BIGINT | Y | Y | 自動採番 |
| 設備種類名 | equipment_type_name | VARCHAR(100) |  | Y |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.10 equipment_masters
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 設備マスタID | equipment_master_id | BIGINT | Y | Y | 自動採番 |
| 設備種類ID | equipment_type_id | BIGINT |  | Y | FK -> equipment_types |
| 設備名 | equipment_name | VARCHAR(100) |  | Y |  |
| 設備情報JSON | equipment_info_json | TEXT |  | Y |  |
| 設備情報HTML | equipment_info_html | TEXT |  |  |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.11 equipments
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 設備ID | equipment_id | BIGINT | Y | Y | 自動採番 |
| 施設ID | facility_id | BIGINT |  | Y | FK -> facilities_tree |
| 設備マスタID | equipment_master_id | BIGINT |  | Y | FK -> equipment_masters |
| 水平角 | yaw | FLOAT8 |  |  |  |
| 垂直角 | pitch | FLOAT8 |  |  |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.12 annotations
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 付箋ID | annotation_id | BIGINT | Y | Y | 自動採番 |
| 施設ID | facility_id | BIGINT |  | Y | FK -> facilities_tree |
| 種別 | annotation_type | CHAR(2) |  | Y | 10:一般、20:緊急 |
| タイトル | annotation_title | VARCHAR(32) |  | Y |  |
| 本文 | annotation_content | VARCHAR(256) |  | Y |  |
| 作成者 | created_by | VARCHAR(128) |  | Y | Keycloak sub |
| 表示期限種別 | display_expire_type | BOOLEAN |  | Y | True:期限あり、False:期限なし |
| 表示期限 | display_expire_at | DATETIME |  |  | display_expire_type=True の場合は必須 |
| 水平角 | yaw | FLOAT8 |  |  |  |
| 垂直角 | pitch | FLOAT8 |  |  |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.13 annotation_type_master
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 付箋種別 | annotation_type | CHAR(2) | Y | Y | 10:一般、20:緊急 |
| 付箋種別名称 | annotation_type_name | VARCHAR(32) |  | Y |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

### 4.14 annotation_comments
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| コメントID | annotation_comment_id | BIGINT | Y | Y | 自動採番 |
| 付箋ID | annotation_id | BIGINT |  | Y | FK -> annotations |
| コメント本文 | comment_text | VARCHAR(128) |  | Y |  |
| 作成者 | created_by | VARCHAR(128) |  | Y | Keycloak sub |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 将来削除API用 |

### 4.15 annotation_files
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 付箋添付ID | annotation_file_id | BIGINT | Y | Y | 自動採番 |
| 付箋ID | annotation_id | BIGINT |  | Y | FK -> annotations |
| ファイルID | file_id | BIGINT |  | Y | FK -> files |

### 4.16 equipment_files
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| 設備ファイルID | equipment_file_id | BIGINT | Y | Y | 自動採番 |
| 設備マスタID | equipment_master_id | BIGINT |  | Y | FK -> equipment_masters |
| ファイルID | file_id | BIGINT |  | Y | FK -> files |

### 4.17 hotspots
| 論理名 | 物理名 | 型 | PK | NN | 備考 |
|--------|--------|----|----|----|------|
| ホットスポットID | hotspot_id | BIGINT | Y | Y | 自動採番 |
| 画像ID | image_id | BIGINT |  | Y | FK -> facility_images |
| 遷移先施設ID | target_id | BIGINT |  | Y |  |
| 水平角 | yaw | FLOAT8 |  | Y |  |
| 垂直角 | pitch | FLOAT8 |  | Y |  |
| 作成日時 | created_at | DATETIME |  | Y |  |
| 更新日時 | updated_at | DATETIME |  | Y |  |
| 削除日時 | deleted_at | DATETIME |  |  | 論理削除 |

## 5. 非保持データ（DB外責務）
- ユーザー実体（ユーザー名、メール、ログインID）: Keycloak 管理。
- keycloak_role_master / user_roles: Keycloak DB管理（本DBでは作成しない）。
- /me の roles、can_use_admin_mode: トークン情報およびKeycloak連携情報から都度導出。
- files の実ファイル本体: 外部ストレージ管理（DBはメタ情報のみ保持）。

## 6. 主要インデックス設計
- facilities_tree: PK(facility_id), IDX(parent_id, sort_order, deleted_at)
- equipment_masters: PK(equipment_master_id)
- equipments: PK(equipment_id)
- annotations: PK(annotation_id)
- files: PK(file_id)
- annotation_files: PK(annotation_file_id)
- equipment_files: PK(equipment_file_id)
- annotation_comments: PK(annotation_comment_id)
- facility_images: PK(image_id)
- maps: PK(map_id)
- hotspots: PK(hotspot_id)
- map_points: PK(map_point_id)
- permission_master: PK(permission_id)
- facility_role_permission: PK(facility_role_permission_id), IDX(facility_id, keycloak_role_id, deleted_at), UQ(facility_id, keycloak_role_id, permission_id) WHERE deleted_at IS NULL
- equipment_types: PK(equipment_type_id)

## 7. 運用上の注意
- 論理削除済みデータ（deleted_at IS NOT NULL）は Webアプリからの一覧取得・詳細取得では返却対象外とする。
- 施設削除時は子施設と関連テーブルの論理削除を同一トランザクションで実施する。
- facilities_tree 更新時の一括保存は、施設本体更新と関連テーブル更新を同一トランザクションで処理する。
- annotation_files / equipment_files の追加・削除は更新リクエストとの差分判定で実施する。
- /facilities/{facilityId}/vr-view の move_points は hotspots、annotation_points は annotations、equipment_points は equipments を参照して生成する。
- 既存ファイルの差替時は新規 file_id を採番し、不要ファイルは deleted_at を更新して論理削除する。
