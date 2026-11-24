-- Dev Agent 전용 role 과 전용 DB 만들기 (공유 PostgreSQL 인스턴스용).
-- 이 파일은 아래 두 가지만 만든다. 다른 DB·role 은 건드리지 않고, 테이블도 만들지 않는다(앱 첫 기동이 만든다).
--
-- 실행(psql 변수 3개 필요 — deploy/README.md §3):
--   psql -U <관리 role> -d postgres -v dbname=<전용 DB> -v dbuser=<전용 role> -v dbpass=<비밀번호> -f create-db.sql
--
-- 관리 role 로 접속해 실행한다(role·DB 를 만들 권한이 필요하므로 이 파일의 role 이 아니다).

\set ON_ERROR_STOP on

-- 전용 role: 로그인만 가능. 그 밖의 권한 속성은 모두 끈다.
-- CONNECTION LIMIT 은 공유 인스턴스 보호용이다(앱 풀 5 + 기동 점검 1 + 여유).
CREATE ROLE :"dbuser" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 10 PASSWORD :'dbpass';

-- 전용 DB: 위 role 이 소유자. 소유자이므로 이 DB 안의 테이블은 만들 수 있다.
CREATE DATABASE :"dbname" OWNER :"dbuser";

-- 전용 DB 에는 PUBLIC(모든 role)의 접속 권한을 거둔다. 소유자는 그대로 접속된다.
REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
