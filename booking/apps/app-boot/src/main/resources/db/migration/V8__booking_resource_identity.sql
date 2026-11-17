-- A requirement may be filled by more than one resource: a party of four seated at two tables is one slot with two
-- resources. The slot code and the resource together are the identity, so the same code may repeat across a booking.
alter table booking_resources drop constraint booking_resources_pkey;
alter table booking_resources add primary key (tenant_id, booking_id, slot_code, resource_id);
