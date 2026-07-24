-- V7__drop_budget_envelope.sql
alter table category add column period text   not null default 'MONTHLY';
alter table category add column amount bigint not null default 0;
alter table category add column slack_loggable boolean not null default true;
alter table category drop column envelope_id;
drop table budget_envelope;
