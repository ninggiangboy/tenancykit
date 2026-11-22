#!/usr/bin/env ruby
# One-off codemod for the caller-surfaces plan: adds @Callers to every controller from the permission catalog.
# Dry-run by default; --write applies. Not part of the product build.
require 'yaml'

ROOT = File.expand_path('../../..', __dir__)
WRITE = ARGV.include?('--write')

CATALOG = {}
Dir[File.join(ROOT, 'contracts/permissions/*.yaml')].sort.each do |file|
  YAML.load_file(file).each { |entry| CATALOG[entry['code']] = entry }
end

CONSTANTS = {}
Dir[File.join(ROOT, 'backend/services/**/*Permissions.java')].each do |file|
  source = File.read(file)
  owner = source[/\bclass\s+(\w+)/, 1] || File.basename(file, '.java')
  source.scan(/static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"/) do |name, code|
    CONSTANTS["#{owner}.#{name}"] = code
  end
end

ORDER = %i[PLATFORM_USER TENANT_USER TENANT_SYSTEM CUSTOMER_SESSION PUBLIC PROVIDER INTERNAL]
PROVIDER_FILES = %w[StripeWebhookController.java HookController.java].freeze
FORCE_CLASS = {
  'TokenEndpointController.java' => { callers: %i[TENANT_SYSTEM INTERNAL], prefix: :none },
  'JwksController.java' => { callers: %i[PUBLIC], prefix: :none }
}.freeze

def ordered(callers)
  ORDER.select { |caller| callers.include?(caller) }
end

def callers_for_code(code)
  entry = CATALOG[code]
  return nil unless entry

  scopes = Array(entry['scopes']).map(&:upcase)
  callers = []
  callers << :PLATFORM_USER if scopes.include?('PLATFORM')
  callers << :TENANT_USER if scopes.include?('TENANT')
  callers << :TENANT_SYSTEM if entry['grantable_to_tenant_system']
  callers << :INTERNAL if scopes.empty?
  callers
end

def annotation(callers, prefix)
  list = ordered(callers)
  args = if list.size == 1
           "Caller.#{list.first}"
         else
           "{#{list.map { |c| "Caller.#{c}" }.join(', ')}}"
         end
  prefix == :none ? "@Callers(value = #{args}, prefix = Callers.Prefix.NONE)" : "@Callers(#{args})"
end

def insert_imports(lines, needed)
  needed.each do |fq|
    next if lines.any? { |line| line.strip == "import #{fq};" }

    package = lines.index { |line| line.start_with?('package ') }
    raise 'no package' unless package

    if lines[package + 1] && lines[package + 1].strip.empty?
      insert_at = package + 2
    else
      lines.insert(package + 1, "\n")
      insert_at = package + 2
    end
    lines.each_with_index do |line, index|
      next unless line.start_with?('import ')

      break if line.strip > "import #{fq};"

      insert_at = index + 1
    end
    lines.insert(insert_at, "import #{fq};\n")
  end
end

files = Dir[File.join(ROOT, 'backend/services/**/*Controller.java')].sort.reject { |f| f.include?('/src/test/') }
problems = []
changes = 0

files.each do |file|
  lines = File.readlines(file)
  class_index = lines.index { |line| line =~ /^(?:public\s+|abstract\s+|final\s+)*(?:class|record)\s+\w+/ }
  next unless class_index
  next unless lines[0...class_index].any? { |line| line.include?('@RestController') }

  basename = File.basename(file)
  forced = FORCE_CLASS[basename]
  class_path = lines[0...class_index].join[/@RequestMapping\((?:[^)]*?"([^"]*)")?/m, 1]

  methods = []
  signature = /^    (?:public\s+|protected\s+|private\s+|static\s+|final\s+|synchronized\s+)*(?:[\w.$<>\[\],?]+\s+)+\w+\s*\(/
  (class_index + 1...lines.size).each do |index|
    next unless lines[index].match?(signature)
    next if lines[index] =~ /^\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+)?record\s/

    block_start = index
    block_start -= 1 while block_start.positive? && !lines[block_start - 1].strip.empty? && lines[block_start - 1] !~ /^\s{0,4}\}/
    block = lines[block_start...index].join
    next unless block.match?(/@(Get|Post|Put|Patch|Delete|Request)Mapping/)

    methods << { index: index, block_start: block_start, block: block }
  end
  next if methods.empty?

  computed = methods.map do |method|
    block = method[:block]
    set = if block.match?(/@RequiresPortalPermission/)
            %i[CUSTOMER_SESSION]
          elsif block.match?(/@RequiresPermission/)
            match = block.match(/@RequiresPermission\(\s*([^)]+?)\s*\)/m)
            unless match
              problems << "#{file}: cannot read @RequiresPermission"
              next method.merge(callers: [])
            end
            raw = match[1].strip
            code = raw.include?('.') ? CONSTANTS[raw] : raw.delete('"')
            callers = code && callers_for_code(code)
            unless callers
              problems << "#{file}: unknown code #{raw.inspect} (resolved #{code.inspect})"
              callers = []
            end
            callers
          elsif block.match?(/@Authenticated/)
            %i[PLATFORM_USER TENANT_USER]
          elsif block.match?(/@PublicEndpoint/)
            PROVIDER_FILES.include?(basename) ? %i[PROVIDER] : %i[PUBLIC]
          elsif class_path.to_s.start_with?('/p')
            %i[PUBLIC]
          else
            problems << "#{file}: no access source for method at line #{method[:index] + 1}"
            []
          end
    method.merge(callers: set)
  end

  if forced
    class_callers = forced[:callers]
    prefix = forced[:prefix]
  else
    counts = computed.group_by { |method| ordered(method[:callers]) }.transform_values(&:size)
    class_callers, = counts.max_by do |callers, count|
      score = callers.count { |caller| !%i[PUBLIC PROVIDER].include?(caller) } * 10 + callers.size
      [count, score]
    end
    prefix = :derived
  end

  overrides = forced ? [] : computed.reject { |method| ordered(method[:callers]) == ordered(class_callers) }

  class_line = annotation(class_callers, prefix)
  puts "#{file.sub(ROOT + '/', '')}: class #{class_line} (#{methods.size} endpoints, #{overrides.size} method overrides)"
  overrides.each { |method| puts "    line #{method[:index] + 1}: #{annotation(method[:callers], :derived)}" }

  next unless WRITE

  overrides.sort_by { |method| -method[:block_start] }.each do |method|
    next if method[:block].include?('@Callers')

    insert_at = method[:block_start]
    (method[:block_start]...method[:index]).each do |index|
      insert_at = index + 1 if lines[index].include?('*/')
    end
    lines.insert(insert_at, "    #{annotation(method[:callers], :derived)}\n")
  end

  if PROVIDER_FILES.include?(basename) && !forced
    lines.each_with_index do |line, index|
      lines[index] = line.sub('@PublicEndpoint', '@PublicEndpoint(caller = Caller.PROVIDER)') if line.include?('@PublicEndpoint') && !line.include?('caller =')
    end
  end

  class_index = lines.index { |line| line =~ /^(?:public\s+|abstract\s+|final\s+)*(?:class|record)\s+\w+/ }
  unless lines[0...class_index].join.include?('@Callers')
    lines.insert(class_index, "#{class_line}\n")
  end
  insert_imports(lines, %w[dev.ngb.core.security.Caller dev.ngb.core.security.Callers])
  File.write(file, lines.join)
  changes += 1
end

puts
puts "#{files.size} controllers, #{changes} written"
puts "PROBLEMS (#{problems.size}):"
problems.each { |problem| puts "  #{problem}" }
