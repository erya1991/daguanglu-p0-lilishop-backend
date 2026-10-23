-- One sid / one key. Redis >= 6.2 (SET PXAT). No writes until every check and JSON encode succeeds.
-- ARGV are plain UTF-8 strings via StringRedisTemplate: operation, expected JSON, replacement JSON.
local operation = ARGV[1]
if #KEYS ~= 1 or #ARGV ~= 3 then return 0 end
if operation ~= 'CREATE' and operation ~= 'ROTATE' and operation ~= 'REVOKE' then return 0 end

local function integer(value)
    return type(value) == 'number' and value == math.floor(value) and value >= 0 and value < 9007199254740991
end
local function identityEqual(a, b)
    return a.subject == b.subject and a.domain == b.domain and a.application == b.application
        and a.bindingId == b.bindingId and a.bindingVersion == b.bindingVersion
end
local function valid(record)
    if type(record) ~= 'table' or type(record.identity) ~= 'table' then return false end
    local identity = record.identity
    local domain = identity.domain
    return record.protocolVersion == 1 and type(record.sid) == 'string' and #record.sid == 32
        and record.sid:match('^[a-f0-9]+$') ~= nil
        and (domain == 'RESIDENT' or domain == 'APPLICANT' or domain == 'STORE' or domain == 'MANAGER')
        and type(identity.subject) == 'string' and #identity.subject > 0
        and type(identity.application) == 'string' and #identity.application > 0
        and (domain == 'MANAGER' or (type(identity.bindingId) == 'string' and #identity.bindingId > 0
            and integer(identity.bindingVersion) and identity.bindingVersion >= 1))
        and (record.status == 'ACTIVE' or record.status == 'REVOKED')
        and integer(record.generation) and record.generation <= 1000000000
        and type(record.accessDigest) == 'string' and #record.accessDigest == 64
        and record.accessDigest:match('^[a-f0-9]+$') ~= nil
        and type(record.refreshDigest) == 'string' and #record.refreshDigest == 64
        and record.refreshDigest:match('^[a-f0-9]+$') ~= nil
        and record.accessDigest ~= record.refreshDigest
        and integer(record.createdAt) and integer(record.accessExpiresAt) and integer(record.absoluteExpiresAt)
        and record.createdAt < record.accessExpiresAt and record.accessExpiresAt <= record.absoluteExpiresAt
end
local expected = cjson.decode(ARGV[2])
local replacement = cjson.decode(ARGV[3])
if not valid(expected) or not valid(replacement) then return 0 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local raw = redis.call('GET', KEYS[1])

if operation == 'CREATE' then
    local lifetime = (replacement.identity.domain == 'RESIDENT' or replacement.identity.domain == 'STORE')
        and 1296000000 or 86400000
    if raw or replacement.status ~= 'ACTIVE' or replacement.generation ~= 0
        or replacement.sid ~= expected.sid or not identityEqual(replacement.identity, expected.identity)
        or replacement.absoluteExpiresAt ~= replacement.createdAt + lifetime
        or replacement.accessExpiresAt > replacement.createdAt + 900000
        or replacement.createdAt > now or replacement.accessExpiresAt <= now then return 0 end
else
    if not raw then return 0 end
    local current = cjson.decode(raw)
    if not valid(current) or current.sid ~= expected.sid or not identityEqual(current.identity, expected.identity)
        or current.createdAt ~= expected.createdAt or current.absoluteExpiresAt ~= expected.absoluteExpiresAt
        or current.absoluteExpiresAt <= now then return 0 end
    if operation == 'ROTATE' then
        if current.status ~= 'ACTIVE' or current.generation ~= expected.generation
            or current.refreshDigest ~= expected.refreshDigest or current.accessDigest ~= expected.accessDigest
            or replacement.sid ~= current.sid or not identityEqual(replacement.identity, current.identity)
            or replacement.createdAt ~= current.createdAt or replacement.absoluteExpiresAt ~= current.absoluteExpiresAt
            or replacement.status ~= 'ACTIVE' or replacement.generation ~= current.generation + 1
            or replacement.accessDigest == current.accessDigest or replacement.refreshDigest == current.refreshDigest
            or replacement.accessExpiresAt <= now then return 0 end
    else
        -- Revocation deliberately ignores the old generation/digests: logout wins a racing refresh.
        if expected.accessExpiresAt <= now then return 0 end
        replacement = current
        replacement.status = 'REVOKED'
    end
end
if replacement.absoluteExpiresAt <= now then return 0 end
local encoded = cjson.encode(replacement)
local deadline = string.format('%.0f', replacement.absoluteExpiresAt)
-- Last and only mutation: an error before here cannot leave a partially rotated Session.
redis.call('SET', KEYS[1], encoded, 'PXAT', deadline)
return 1
